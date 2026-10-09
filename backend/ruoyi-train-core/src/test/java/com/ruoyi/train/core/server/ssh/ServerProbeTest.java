package com.ruoyi.train.core.server.ssh;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.train.core.server.domain.*;
import static com.ruoyi.train.core.server.ssh.SshGateway.*;

public class ServerProbeTest
{
    public static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    public static ServerRecord server(long id) { return new ServerRecord(id, "server", "host" + id, 22, "SHA256:test", true, NOW, NOW); }
    public static ServerAccountRecord account(long serverId, long id) { return new ServerAccountRecord(id, serverId, 7L, "linux", "PASSWORD", "encrypted", null, true, "/tmp", null, true, "UNKNOWN", null, null, null, NOW, NOW); }

    public static class FixtureGateway implements SshGateway
    {
        public int calls;
        public ErrorCode failure;
        public final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        public final Map<List<String>, CommandResult> replies = new HashMap<>();
        public FixtureGateway()
        {
            replies.put(List.of("cat", "/proc/loadavg"), new CommandResult(0, "0.50 0.20 0.10 1/300 42\n", ""));
            replies.put(List.of("cat", "/proc/meminfo"), new CommandResult(0, "MemTotal: 10000 kB\nMemAvailable: 4000 kB\n", ""));
            replies.put(List.of("df", "-Pk"), new CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/sda 20000 5000 15000 25% /\ntmpfs 1000 0 1000 0% /run\n", ""));
            replies.put(List.of("nvidia-smi", "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version", "--format=csv,noheader,nounits"), new CommandResult(0, "GPU-same, A100, 0, 80000, 1000, 20, Disabled, 550\n", ""));
            replies.put(List.of("nvidia-smi", "--query-compute-apps=gpu_uuid,pid,process_name,used_memory", "--format=csv,noheader,nounits"), new CommandResult(0, "GPU-same, 123, [Not Found], 1000\n", ""));
            replies.put(List.of("systemctl", "--user", "is-system-running"), new CommandResult(0, "running\n", ""));
            replies.put(List.of("loginctl", "show-user", "--property=Linger"), new CommandResult(0, "Linger=yes\n", ""));
        }
        public HostKeyPreview preview(String host, int port, Duration timeout) { throw new AssertionError("must use pinned session"); }
        public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account, Function<SshSession,T> operation)
        {
            calls++;
            if (failure != null) throw new SshException(failure);
            return operation.apply(new SshSession() {
                public CommandResult exec(List<String> command, Duration timeout, int maxBytes) {
                    assertTrue(timeout.compareTo(DEFAULT_COMMAND_TIMEOUT) <= 0);
                    assertTrue(maxBytes > 0 && maxBytes <= 65536);
                    commands.add(command);
                    return Objects.requireNonNull(replies.get(command), "unapproved command");
                }
                public void close() { }
            });
        }
    }

    @Test void collectsFixedBoundedHostAndGpuFacts()
    {
        FixtureGateway gateway = new FixtureGateway();
        var result = new ServerProbe(gateway).collect(server(1), account(1, 11), NOW);
        assertEquals(1L, result.serverId());
        assertEquals(11L, result.accountId());
        assertEquals(0.5, result.load1());
        assertEquals(10000, result.memoryTotalKiB());
        assertEquals(4000, result.memoryAvailableKiB());
        assertEquals(20000, result.diskTotalKiB());
        assertEquals(15000, result.diskAvailableKiB());
        assertEquals(Map.of("ssh", true, "gpu", true, "systemdUser", true, "persistentUserManager", true), result.capabilities());
        assertEquals("ONLINE", result.status());
        assertEquals(7, gateway.commands.size());
    }

    @Test void missingNvidiaOnlyDisablesGpuCapability()
    {
        FixtureGateway gateway = new FixtureGateway();
        gateway.replies.replaceAll((command, reply) -> command.get(0).equals("nvidia-smi") ? new CommandResult(127, "", "not found") : reply);
        var result = new ServerProbe(gateway).collect(server(1), account(1, 11), NOW);
        assertEquals(Map.of("ssh", true, "gpu", false, "systemdUser", true, "persistentUserManager", true), result.capabilities());
        assertTrue(result.gpus().isEmpty());
        assertEquals("DEGRADED", result.status());
    }

    @Test void failedComputeQueryCannotBeTreatedAsIdle()
    {
        FixtureGateway gateway = new FixtureGateway();
        gateway.replies.replaceAll((command, reply) -> command.get(1).startsWith("--query-compute-apps") ? new CommandResult(1, "", "denied") : reply);
        assertThrows(SshException.class, () -> new ServerProbe(gateway).collect(server(1), account(1, 11), NOW));
    }

    @Test void malformedHostOutputFailsInsteadOfPersistingZeroFacts()
    {
        FixtureGateway gateway = new FixtureGateway();
        gateway.replies.put(List.of("cat", "/proc/meminfo"), new CommandResult(0, "MemTotal: 1000 kB\nMemAvailable: 2000 kB", ""));
        assertThrows(IllegalArgumentException.class, () -> new ServerProbe(gateway).collect(server(1), account(1, 11), NOW));
    }
}
