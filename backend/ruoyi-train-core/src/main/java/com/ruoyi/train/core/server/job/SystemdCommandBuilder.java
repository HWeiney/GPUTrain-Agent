package com.ruoyi.train.core.server.job;

import java.nio.file.Path;
import java.util.*;

/** Builds deterministic remote unit identity and a fixed, shell-escaped training script. */
public final class SystemdCommandBuilder
{
    private static final String UNIT_PREFIX = "gputrain-";
    private static final String PROPERTIES = "LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup";
    private static final Set<String> PROFILE_ENVIRONMENT = Set.of("MODEL_PATH", "PYTHON_BIN", "NPROC_PER_NODE",
            "MAX_STEPS", "RUN_MODE", "AUTO_EVAL", "TRAIN_EVAL_STRATEGY", "RUN_ID");

    public record RemoteJobIdentity(long jobId, UUID jobUuid, long serverId, long accountId,
            String allowedRoot, String unitName, String workingDirectory, String scriptPath,
            String stdoutPath, String stderrPath)
    {
    }

    public record FixedTrainingProfile(String workingDirectory, String executable,
            List<String> arguments, Map<String, String> environment, long timeoutSeconds)
    {
        public FixedTrainingProfile
        {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    public RemoteJobIdentity identity(long jobId, UUID jobUuid, long serverId,
            long accountId, String allowedRoot)
    {
        if (jobId < 1 || serverId < 1 || accountId < 1 || jobUuid == null) throw invalid();
        Path root = canonicalAbsolute(allowedRoot);
        String id = jobUuid.toString().toLowerCase(Locale.ROOT);
        String directory = root.resolve(".gputrain").resolve("jobs").resolve(id).toString();
        return new RemoteJobIdentity(jobId, jobUuid, serverId, accountId, root.toString(),
                UNIT_PREFIX + id + ".service", directory,
                directory + "/run.sh", directory + "/stdout.log", directory + "/stderr.log");
    }

    public List<String> startCommand(RemoteJobIdentity job)
    {
        Objects.requireNonNull(job);
        return List.of("systemd-run", "--user", "--no-block", "--unit=" + checkedUnit(job.unitName()),
                "--property=KillMode=control-group",
                "--property=WorkingDirectory=" + job.workingDirectory(),
                "--property=StandardOutput=append:" + job.stdoutPath(),
                "--property=StandardError=append:" + job.stderrPath(),
                "/usr/bin/bash", job.scriptPath());
    }

    public List<String> inspectCommand(RemoteJobIdentity job)
    {
        Objects.requireNonNull(job);
        return List.of("systemctl", "--user", "show", checkedUnit(job.unitName()),
                "--no-pager", "--property=" + PROPERTIES);
    }

    public List<String> stopCommand(RemoteJobIdentity job)
    {
        Objects.requireNonNull(job);
        return List.of("systemctl", "--user", "stop", checkedUnit(job.unitName()));
    }

    public String script(RemoteJobIdentity job, FixedTrainingProfile profile, List<String> gpuUuids)
    {
        Objects.requireNonNull(job);
        Objects.requireNonNull(profile);
        Path work = requireWithin(job.allowedRoot(), profile.workingDirectory());
        if (!"/usr/bin/bash".equals(profile.executable()) || profile.timeoutSeconds() < 1 || profile.timeoutSeconds() > 86400
                || profile.arguments().isEmpty() || profile.arguments().size() > 32) throw invalid();
        for (String argument : profile.arguments())
        {
            checkedText(argument, 4096);
            Path argumentPath = Path.of(argument);
            if (!argumentPath.normalize().equals(argumentPath) || hasParentTraversal(argumentPath)) throw invalid();
        }
        List<String> gpus = checkedGpus(gpuUuids);
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\nset -euo pipefail\ncd -- ")
                .append(quote(work.toString())).append("\nexport CUDA_VISIBLE_DEVICES=")
                .append(quote(String.join(",", gpus))).append('\n');
        for (var entry : new TreeMap<>(profile.environment()).entrySet())
        {
            if (!PROFILE_ENVIRONMENT.contains(entry.getKey()) || !entry.getKey().matches("[A-Z_][A-Z0-9_]{0,63}")) throw invalid();
            checkedText(entry.getValue(), 4096);
            if ("MAX_STEPS".equals(entry.getKey()))
            {
                int steps;
                try { steps = Integer.parseInt(entry.getValue()); } catch (RuntimeException ignored) { throw invalid(); }
                if (steps < 1 || steps > 5) throw invalid();
            }
            script.append("export ").append(entry.getKey()).append('=').append(quote(entry.getValue())).append('\n');
        }
        script.append("exec ").append(quote(profile.executable()));
        for (String argument : profile.arguments()) script.append(' ').append(quote(argument));
        script.append('\n');
        return script.toString();
    }

    public static String quote(String value)
    {
        checkedText(value, 4096);
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static List<String> checkedGpus(List<String> gpus)
    {
        if (gpus == null || gpus.size() != 3 || new HashSet<>(gpus).size() != 3
                || gpus.stream().anyMatch(gpu -> gpu == null || !gpu.matches("GPU-[A-Za-z0-9-]{1,120}"))) throw invalid();
        return List.copyOf(gpus);
    }

    private static Path canonicalAbsolute(String value)
    {
        checkedText(value, 1024);
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path) || value.endsWith("/")) throw invalid();
        return path;
    }

    private static Path requireWithin(String root, String candidate)
    {
        Path allowed = canonicalAbsolute(root);
        Path path = canonicalAbsolute(candidate);
        if (path.equals(allowed) || !path.startsWith(allowed)) throw invalid();
        return path;
    }

    private static boolean hasParentTraversal(Path path)
    { for (Path part : path) if (part.toString().equals("..")) return true; return false; }

    private static String checkedUnit(String value)
    {
        if (value == null || !value.matches("gputrain-[0-9a-fA-F-]{36}\\.service")) throw invalid();
        return value;
    }

    private static void checkedText(String value, int limit)
    {
        if (value == null || value.isEmpty() || value.length() > limit
                || value.chars().anyMatch(ch -> ch < 32 || ch == 127)) throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_REMOTE_JOB"); }
}
