package com.ruoyi.train.runner.process;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.protocol.*;

public final class ProcessPolicy
{
    private final RunnerConfig config;
    public ProcessPolicy(RunnerConfig config) { this.config = Objects.requireNonNull(config); }

    public ValidatedProcessSpec validate(ProcessSpec spec)
    {
        Path requested = Path.of(spec.workingDirectory()).toAbsolutePath().normalize();
        Path work;
        try { work = requested.toRealPath(); }
        catch (IOException failure) { throw rejected(RunnerErrorCode.WORK_DIR_OUTSIDE_ALLOWED_ROOT); }
        if (!work.startsWith(config.allowedRoot()))
            throw rejected(hasAllowedAncestor(requested) ? RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE : RunnerErrorCode.WORK_DIR_OUTSIDE_ALLOWED_ROOT);
        if (!Files.isDirectory(work)) throw rejected(RunnerErrorCode.WORK_DIR_OUTSIDE_ALLOWED_ROOT);
        Path executable;
        try { executable = Path.of(spec.executable()).toRealPath(); }
        catch (IOException failure) { throw rejected(RunnerErrorCode.EXECUTABLE_NOT_ALLOWED); }
        if (!config.executableAllowlist().contains(executable) || !Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw rejected(RunnerErrorCode.EXECUTABLE_NOT_ALLOWED);
        if (!config.environmentAllowlist().containsAll(spec.environment().keySet())
                || spec.environment().keySet().stream().anyMatch(ProcessPolicy::protectedEnvironment)
                || spec.environment().entrySet().stream().anyMatch(e -> e.getKey().isEmpty() || e.getKey().contains("=") || e.getKey().contains("\0") || e.getValue().contains("\0")))
            throw rejected(RunnerErrorCode.ENVIRONMENT_NOT_ALLOWED);
        if (spec.arguments().stream().anyMatch(a -> a.contains("\0"))) throw new IllegalArgumentException("Arguments cannot contain NUL");
        if (executable.getFileName().toString().equals("bash")) validateTrainingCommand(spec);
        Path logs;
        try
        {
            Path state = safeDirectory(config.allowedRoot().resolve(".gputrain"));
            logs = safeDirectory(state.resolve("logs"));
        }
        catch (IOException failure) { throw rejected(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE); }
        String logId = UUID.randomUUID().toString();
        return new ValidatedProcessSpec(work, executable, spec.arguments(), spec.environment(), spec.timeoutSeconds(),
            spec.idempotencyKey(), logs.resolve(logId + ".stdout.log"), logs.resolve(logId + ".stderr.log"));
    }

    private static void validateTrainingCommand(ProcessSpec spec)
    {
        List<String> expected = List.of(
                "/model/hanw/ms-swift-imp3-research/fintune_val_shell/run_swift_docowl2_logidoc.sh",
                "/model/hanw/ms-swift-imp3-research/configs/ocr_evidence/docowl2_srfund.env");
        if (!spec.arguments().equals(expected))
            throw rejected(RunnerErrorCode.EXECUTABLE_NOT_ALLOWED);
        Map<String, String> env = spec.environment();
        Set<String> expectedKeys = Set.of("CUDA_VISIBLE_DEVICES", "MODEL_PATH", "PYTHON_BIN",
                "NPROC_PER_NODE", "MAX_STEPS", "RUN_MODE", "AUTO_EVAL", "TRAIN_EVAL_STRATEGY", "RUN_ID");
        if (!"/model/hanw/ms-swift-imp3-research/pretrained-imp/DocOwl2".equals(env.get("MODEL_PATH"))
                || !"/home/hanw/.conda/envs/swift/bin/python".equals(env.get("PYTHON_BIN"))
                || !"3".equals(env.get("NPROC_PER_NODE"))
                || !"foreground".equals(env.get("RUN_MODE"))
                || !"False".equals(env.get("AUTO_EVAL"))
                || !"no".equals(env.get("TRAIN_EVAL_STRATEGY"))
                || !env.getOrDefault("MAX_STEPS", "").matches("[1-5]")
                || !env.getOrDefault("RUN_ID", "").matches("gputrain-[A-Za-z0-9._-]{1,100}")
                || !env.keySet().equals(expectedKeys)
                || !env.getOrDefault("CUDA_VISIBLE_DEVICES", "").matches("GPU-[A-Fa-f0-9-]+(,GPU-[A-Fa-f0-9-]+){2}"))
            throw rejected(RunnerErrorCode.ENVIRONMENT_NOT_ALLOWED);
    }

    private Path safeDirectory(Path path) throws IOException
    {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            Path resolved = path.toRealPath();
            if (!resolved.startsWith(config.allowedRoot()) || !Files.isDirectory(resolved))
                throw rejected(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE);
            return resolved;
        }
        Files.createDirectory(path);
        Path resolved = path.toRealPath();
        if (!resolved.startsWith(config.allowedRoot())) throw rejected(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE);
        return resolved;
    }
    private boolean hasAllowedAncestor(Path path)
    {
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent())
        {
            try { if (parent.toRealPath().startsWith(config.allowedRoot())) return true; }
            catch (IOException ignored) { }
        }
        return false;
    }
    private static ProcessPolicyException rejected(RunnerErrorCode code)
    { return new ProcessPolicyException(code, "Process rejected by local policy: " + code); }
    private static boolean protectedEnvironment(String name)
    {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("PATH") || upper.startsWith("LD_") || upper.startsWith("DYLD_")
            || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(upper)
            || upper.contains("SECRET") || upper.contains("CREDENTIAL") || upper.contains("ENROLLMENT");
    }
}
