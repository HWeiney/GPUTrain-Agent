package com.ruoyi.train.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RunnerProtocolTest
{
    @Test
    void processSpecDefensivelyCopiesArgumentsAndEnvironment()
    {
        List<String> arguments = new ArrayList<>(List.of("--mode", "sleep"));
        Map<String, String> environment = new HashMap<>(Map.of("CUDA_VISIBLE_DEVICES", "0"));
        ProcessSpec spec = new ProcessSpec("/tmp/work", "/usr/bin/java", arguments, environment, 60, "fixture-1");
        arguments.add("changed");
        environment.put("PATH", "/tmp");
        assertEquals(List.of("--mode", "sleep"), spec.arguments());
        assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "0"), spec.environment());
        assertThrows(UnsupportedOperationException.class, () -> spec.arguments().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> spec.environment().put("PATH", "/tmp"));
    }

    @Test
    void processSpecRejectsInvalidRequiredValues()
    {
        assertThrows(NullPointerException.class,
            () -> new ProcessSpec(null, "/usr/bin/java", List.of(), Map.of(), 60, "key"));
        assertThrows(NullPointerException.class,
            () -> new ProcessSpec("/tmp", null, List.of(), Map.of(), 60, "key"));
        assertThrows(NullPointerException.class,
            () -> new ProcessSpec("/tmp", "/usr/bin/java", null, Map.of(), 60, "key"));
        assertThrows(NullPointerException.class,
            () -> new ProcessSpec("/tmp", "/usr/bin/java", List.of(), null, 60, "key"));
        assertThrows(IllegalArgumentException.class,
            () -> new ProcessSpec("/tmp", "/usr/bin/java", List.of(), Map.of(), 0, "key"));
        assertThrows(IllegalArgumentException.class,
            () -> new ProcessSpec("/tmp", "/usr/bin/java", List.of(), Map.of(), 60, " "));
    }

    @Test
    void commandTransitionsFollowApprovedStateGraph()
    {
        RunnerCommandStatus.QUEUED.requireTransitionTo(RunnerCommandStatus.LEASED);
        RunnerCommandStatus.QUEUED.requireTransitionTo(RunnerCommandStatus.REJECTED);
        RunnerCommandStatus.LEASED.requireTransitionTo(RunnerCommandStatus.RUNNING);
        RunnerCommandStatus.LEASED.requireTransitionTo(RunnerCommandStatus.REJECTED);
        RunnerCommandStatus.RUNNING.requireTransitionTo(RunnerCommandStatus.SUCCEEDED);
        RunnerCommandStatus.RUNNING.requireTransitionTo(RunnerCommandStatus.FAILED);
        RunnerCommandStatus.RUNNING.requireTransitionTo(RunnerCommandStatus.STOPPED);
        assertTrue(RunnerCommandStatus.SUCCEEDED.isTerminal());
        assertFalse(RunnerCommandStatus.RUNNING.isTerminal());
        assertThrows(IllegalStateException.class,
            () -> RunnerCommandStatus.SUCCEEDED.requireTransitionTo(RunnerCommandStatus.RUNNING));
        assertThrows(IllegalStateException.class,
            () -> RunnerCommandStatus.QUEUED.requireTransitionTo(RunnerCommandStatus.SUCCEEDED));
    }

    @Test
    void leasesRequireThePayloadForTheirCommandType()
    {
        UUID commandId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant until = Instant.now();
        ProcessSpec process = new ProcessSpec("/tmp", "/usr/bin/java", List.of(), Map.of(), 60, "key");
        assertEquals(process, new RunnerCommandLease(commandId, RunnerCommandType.START_PROCESS,
            process, null, until).process());
        assertEquals(targetId, new RunnerCommandLease(commandId, RunnerCommandType.STOP_PROCESS,
            null, targetId, until).targetCommandId());
        assertThrows(IllegalArgumentException.class,
            () -> new RunnerCommandLease(commandId, RunnerCommandType.START_PROCESS, null, null, until));
        assertThrows(IllegalArgumentException.class,
            () -> new RunnerCommandLease(commandId, RunnerCommandType.STOP_PROCESS, process, targetId, until));
        assertThrows(NullPointerException.class,
            () -> new RunnerCommandLease(null, RunnerCommandType.START_PROCESS, process, null, until));
    }

    @Test
    void credentialsDoNotRevealSecretInToString()
    {
        RunnerCredentials credentials = new RunnerCredentials("runner-1", "runner", "private-secret");
        assertEquals("RunnerCredentials[runnerId=runner-1]", credentials.toString());
    }

    @Test
    void registrationDoesNotRevealEnrollmentTokenInToString()
    {
        RunnerRegistration registration = new RunnerRegistration(1, "enrollment-secret", "runner", "linux");
        assertEquals("RunnerRegistration[enrollmentId=1, runnerName=runner, linuxUsername=linux]",
            registration.toString());
    }

    @Test
    void transportRecordsRejectMissingRequiredFields()
    {
        UUID commandId = UUID.randomUUID();
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () -> new RunnerRegistration(1, null, "runner", "linux"));
        assertThrows(NullPointerException.class, () -> new RunnerCredentials("id", "runner", null));
        assertThrows(NullPointerException.class,
            () -> new RunnerCommandReport(null, RunnerCommandStatus.RUNNING, null, null, null, null, null));
        assertThrows(NullPointerException.class,
            () -> new RunnerProcessSnapshot(commandId, 1, now, "/usr/bin/java", "hash", null, null, null));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID,
            new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID, "invalid").code());
    }
}
