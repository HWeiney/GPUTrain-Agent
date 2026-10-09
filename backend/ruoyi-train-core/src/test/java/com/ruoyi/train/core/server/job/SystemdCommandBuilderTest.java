package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemdCommandBuilderTest
{
    private final SystemdCommandBuilder builder = new SystemdCommandBuilder();
    private final UUID uuid = UUID.fromString("e53780aa-cad6-4c44-b088-c9a681afe40f");

    private SystemdCommandBuilder.FixedTrainingProfile profile(String workDir, List<String> args)
    {
        return new SystemdCommandBuilder.FixedTrainingProfile(workDir, "/usr/bin/bash", args,
                Map.of("MAX_STEPS", "3", "RUN_MODE", "foreground"), 3600);
    }

    @Test
    void derivesStableUnitAndPrivateWorkPathsFromUuid()
    {
        var identity = builder.identity(72, uuid, 4, 8, "/home/alice/training");
        assertEquals("gputrain-e53780aa-cad6-4c44-b088-c9a681afe40f.service", identity.unitName());
        assertEquals("/home/alice/training/.gputrain/jobs/e53780aa-cad6-4c44-b088-c9a681afe40f", identity.workingDirectory());
        assertEquals("/home/alice/training/.gputrain/jobs/e53780aa-cad6-4c44-b088-c9a681afe40f/run.sh", identity.scriptPath());
    }

    @Test
    void createsOnlyFixedSystemdPropertiesAndShellEscapesEachArgument()
    {
        var identity = builder.identity(72, uuid, 4, 8, "/home/alice/training");
        var profile = profile("/home/alice/training/project", List.of("/home/alice/training/run.sh", "config.yml"));
        var command = builder.startCommand(identity);
        assertEquals(List.of("systemd-run", "--user", "--no-block", "--unit=" + identity.unitName(),
                "--property=KillMode=control-group",
                "--property=WorkingDirectory=" + identity.workingDirectory(),
                "--property=StandardOutput=append:" + identity.stdoutPath(),
                "--property=StandardError=append:" + identity.stderrPath(),
                "/usr/bin/bash", identity.scriptPath()), command);
        String script = builder.script(identity, profile, List.of("GPU-1234-abcd", "GPU-5678-efab", "GPU-9012-cdef"));
        assertTrue(script.contains("CUDA_VISIBLE_DEVICES='GPU-1234-abcd,GPU-5678-efab,GPU-9012-cdef'"));
        assertTrue(script.contains("export MAX_STEPS='3'"));
        assertTrue(script.contains("exec '/usr/bin/bash' '/home/alice/training/run.sh' 'config.yml'"));
        assertEquals(List.of("systemctl", "--user", "show", identity.unitName(),
                "--no-pager", "--property=LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup"), builder.inspectCommand(identity));
        assertEquals(List.of("systemctl", "--user", "stop", identity.unitName()), builder.stopCommand(identity));
    }

    @Test
    void rejectsUntrustedPathsProfileFieldsAndGpuInputs()
    {
        assertThrows(IllegalArgumentException.class, () -> builder.identity(72, uuid, 4, 8, "/home/alice/../root"));
        var identity = builder.identity(72, uuid, 4, 8, "/home/alice/training");
        assertThrows(IllegalArgumentException.class, () -> builder.script(identity,
                profile("/tmp", List.of("/home/alice/training/run.sh")), List.of("GPU-1", "GPU-2", "GPU-3")));
        assertThrows(IllegalArgumentException.class, () -> builder.script(identity,
                profile("/home/alice/training", List.of("/home/alice/training/../escape.sh")), List.of("GPU-1", "GPU-2", "GPU-3")));
        assertThrows(IllegalArgumentException.class, () -> builder.script(identity,
                profile("/home/alice/training", List.of("/home/alice/training/a.sh")), List.of("GPU-1;touch /tmp/x", "GPU-2", "GPU-3")));
        assertThrows(IllegalArgumentException.class, () -> builder.script(identity,
                profile("/home/alice/training", List.of("/home/alice/training/a.sh")), List.of("GPU-1", "GPU-2")));
    }
}
