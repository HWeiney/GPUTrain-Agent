package com.ruoyi.train.core.server.ssh;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.server.domain.*;
import static com.ruoyi.train.core.server.ssh.SshGateway.*;

@Component
public class ServerProbe
{
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 65536;
    private static final List<String> GPU_QUERY = List.of("nvidia-smi",
        "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version", "--format=csv,noheader,nounits");
    private static final List<String> PROCESS_QUERY = List.of("nvidia-smi",
        "--query-compute-apps=gpu_uuid,pid,process_name,used_memory", "--format=csv,noheader,nounits");
    private final SshGateway gateway;
    private final SshNvidiaSmiParser parser = new SshNvidiaSmiParser();

    public ServerProbe(SshGateway gateway) { this.gateway = gateway; }

    public ServerProbeResult collect(ServerRecord server, ServerAccountRecord account, Instant now)
    {
        return gateway.withVerifiedSession(server, account, session -> {
            double load = load(required(run(session, List.of("cat", "/proc/loadavg"))));
            long[] memory = memory(required(run(session, List.of("cat", "/proc/meminfo"))));
            long[] disk = disk(required(run(session, List.of("df", "-Pk"))));
            CommandResult gpu = run(session, GPU_QUERY);
            SshNvidiaSmiParser.Query query = gpu.exitCode() == 127
                ? new SshNvidiaSmiParser.Query(List.of(), null)
                : parser.parse(required(gpu), required(run(session, PROCESS_QUERY)), now);
            boolean hasGpu = !query.gpus().isEmpty();
            CommandResult manager = run(session, List.of("systemctl", "--user", "is-system-running"));
            boolean systemd = (manager.exitCode() == 0 && manager.stdout().strip().equals("running"))
                || (manager.exitCode() == 1 && manager.stdout().strip().equals("degraded"));
            CommandResult linger = run(session, List.of("loginctl", "show-user", account.linuxUsername(), "--property=Linger"));
            boolean persistent = systemd && linger.exitCode() == 0 && linger.stdout().strip().equals("Linger=yes");
            boolean appendLogs = false;
            if (persistent)
            {
                CommandResult appendProbe = run(session, List.of("systemd-run", "--user", "--wait", "--collect",
                    "--unit=gputrain-capability-probe.service",
                    "--property=StandardOutput=append:/dev/null",
                    "--property=StandardError=append:/dev/null", "/usr/bin/true"));
                appendLogs = appendProbe.exitCode() == 0;
            }
            return new ServerProbeResult(server.serverId(), account.accountId(), now, load,
                memory[0], memory[1], disk[0], disk[1], query.gpus(), query.driverVersion(),
                Map.of("ssh", true, "gpu", hasGpu, "systemdUser", systemd,
                    "persistentUserManager", persistent, "appendLogs", appendLogs),
                hasGpu && systemd && persistent ? "ONLINE" : "DEGRADED", hasGpu ? null : "GPU_UNAVAILABLE");
        });
    }

    private static CommandResult run(SshSession session, List<String> command) { return session.exec(command, TIMEOUT, MAX_BYTES); }
    private static String required(CommandResult result)
    {
        if (result.exitCode() != 0) throw new SshException(ErrorCode.COMMAND_FAILED);
        return result.stdout();
    }
    private static List<String> lines(String text)
    {
        if (text == null || text.length() > MAX_BYTES) throw invalid();
        List<String> lines = text.lines().toList();
        if (lines.size() > 512) throw invalid();
        return lines;
    }
    private static double load(String text)
    {
        if (lines(text).size() != 1) throw invalid();
        String[] fields = text.strip().split("\\s+");
        if (fields.length != 5) throw invalid();
        try { double value = Double.parseDouble(fields[0]); if (!Double.isFinite(value) || value < 0) throw invalid(); return value; }
        catch (NumberFormatException e) { throw invalid(); }
    }
    private static long[] memory(String text)
    {
        Map<String, Long> values = new HashMap<>();
        for (String line : lines(text))
        {
            String[] f = line.strip().split("\\s+");
            if (f.length > 0 && Set.of("MemTotal:", "MemAvailable:").contains(f[0]))
            {
                if (f.length != 3 || !f[2].equals("kB") || values.put(f[0], number(f[1])) != null) throw invalid();
            }
        }
        Long total = values.get("MemTotal:"), available = values.get("MemAvailable:");
        if (total == null || available == null || total == 0 || available > total) throw invalid();
        return new long[] {total, available};
    }
    /** Root filesystem totals in POSIX df's 1024-byte units. Other mounts are not summed. */
    private static long[] disk(String text)
    {
        long[] root = null;
        for (String line : lines(text))
        {
            String[] f = line.strip().split("\\s+");
            if (f.length >= 2 && f[f.length - 1].equals("/"))
            {
                if (f.length != 6 || root != null) throw invalid();
                long total = number(f[1]), used = number(f[2]), available = number(f[3]);
                if (total == 0 || used > total || available > total) throw invalid();
                root = new long[] {total, available};
            }
        }
        if (root == null) throw invalid();
        return root;
    }
    private static long number(String value)
    {
        try { long n = Long.parseLong(value); if (n < 0) throw invalid(); return n; }
        catch (NumberFormatException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CORRUPT_OUTPUT"); }
}
