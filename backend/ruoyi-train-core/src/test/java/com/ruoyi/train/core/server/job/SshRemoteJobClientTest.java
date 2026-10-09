package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.ssh.SshGateway;

class SshRemoteJobClientTest
{
    private static final String ACTIVE = "LoadState=loaded\nActiveState=active\nSubState=running\nResult=success\nExecMainPID=42\nExecMainStatus=0\nControlGroup=/user.slice/job.service\n";
    private static final String MISSING = "LoadState=not-found\nActiveState=inactive\nSubState=dead\nResult=success\nExecMainPID=0\nExecMainStatus=0\nControlGroup=\n";
    private final ServerRecord server = new ServerRecord(4L, "node", "host", 22, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", true, Instant.now(), Instant.now());
    private final ServerAccountRecord account = new ServerAccountRecord(8L, 4L, 2L, "alice", "PASSWORD", "cipher", null, true, "/home/alice/training", "{}", true, "ONLINE", null, null, null, Instant.now(), Instant.now());
    private final SystemdCommandBuilder builder = new SystemdCommandBuilder();
    private final SystemdCommandBuilder.RemoteJobIdentity job = builder.identity(72, UUID.fromString("e53780aa-cad6-4c44-b088-c9a681afe40f"), 4, 8, "/home/alice/training");
    private final SystemdCommandBuilder.FixedTrainingProfile profile = new SystemdCommandBuilder.FixedTrainingProfile(
            "/home/alice/training/project", "/usr/bin/bash", List.of("run.sh"), Map.of("MAX_STEPS", "3"), 3600);

    @Test void startDoesNotDuplicateAnAlreadyLoadedUnit()
    {
        FakeGateway gateway = new FakeGateway(ACTIVE);
        assertTrue(new SshRemoteJobClient(gateway).start(server, account, job, profile, gpus()).active());
        assertEquals(1, gateway.session.commands.size());
        assertNull(gateway.session.uploaded);
    }

    @Test void startUploadsScriptBeforeLaunchAndInspectsAgain()
    {
        FakeGateway gateway = new FakeGateway(MISSING, ACTIVE);
        assertTrue(new SshRemoteJobClient(gateway).start(server, account, job, profile, gpus()).active());
        assertTrue(gateway.session.uploaded.contains("CUDA_VISIBLE_DEVICES="));
        assertEquals(List.of("inspect", "start", "inspect"), gateway.session.operations);
    }

    @Test void stopInspectsStopsThenVerifies()
    {
        FakeGateway gateway = new FakeGateway(ACTIVE, MISSING);
        assertFalse(new SshRemoteJobClient(gateway).stop(server, account, job).active());
        assertEquals(List.of("inspect", "stop", "inspect"), gateway.session.operations);
    }

    @Test void rejectsMismatchedExecutionIdentityBeforeConnecting()
    {
        FakeGateway gateway = new FakeGateway(ACTIVE);
        var wrongServerJob = builder.identity(72, UUID.randomUUID(), 9, 8, "/home/alice/training");
        assertThrows(IllegalArgumentException.class, () -> new SshRemoteJobClient(gateway).inspect(server, account, wrongServerJob));
        assertFalse(gateway.connected);
    }

    private static List<String> gpus() { return List.of("GPU-1234-abcd", "GPU-5678-efab", "GPU-9012-cdef"); }

    private static final class FakeGateway implements SshGateway
    {
        final FakeSession session;
        boolean connected;
        FakeGateway(String... replies) { session = new FakeSession(new ArrayDeque<>(List.of(replies))); }
        @Override public HostKeyPreview preview(String host, int port, java.time.Duration timeout) { throw new UnsupportedOperationException(); }
        @Override public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account, Function<SshSession, T> operation)
        { connected = true; return operation.apply(session); }
    }

    private static final class FakeSession implements SshGateway.SshSession
    {
        final Queue<String> states;
        final List<String> operations = new ArrayList<>();
        final List<List<String>> commands = new ArrayList<>();
        String uploaded;
        FakeSession(Queue<String> states) { this.states = states; }
        @Override public SshGateway.CommandResult exec(List<String> command, java.time.Duration timeout, int maxBytes)
        {
            commands.add(command);
            if (command.get(0).equals("systemd-run")) { operations.add("start"); return new SshGateway.CommandResult(0, "", ""); }
            if (command.get(2).equals("stop")) { operations.add("stop"); return new SshGateway.CommandResult(0, "", ""); }
            operations.add("inspect");
            return new SshGateway.CommandResult(0, states.remove(), "");
        }
        @Override public void uploadPrivateFile(String root, String dir, String file, byte[] content, int maxBytes)
        { uploaded = new String(content, java.nio.charset.StandardCharsets.UTF_8); }
        @Override public void close() { }
    }
}
