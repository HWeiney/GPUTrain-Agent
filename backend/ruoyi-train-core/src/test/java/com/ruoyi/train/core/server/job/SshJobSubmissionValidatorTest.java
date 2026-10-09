package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.job.service.JobSubmission;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService.DeviceView;
import com.ruoyi.train.core.server.service.ServerInventoryService.*;
import com.ruoyi.train.core.server.domain.ServerSnapshotRecord;

class SshJobSubmissionValidatorTest
{
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final List<String> GPUS = List.of("GPU-aaaa", "GPU-bbbb", "GPU-cccc");

    @Test void acceptsFreshServerCapabilitiesAndExactlyThreeSelectedGpus()
    {
        var prepared = new SshJobSubmissionValidator().validate(submission(), detail(true), devices(true), NOW,
                UUID.fromString("e53780aa-cad6-4c44-b088-c9a681afe40f"));
        assertEquals("gputrain-e53780aa-cad6-4c44-b088-c9a681afe40f.service", prepared.unitName());
        assertEquals(Set.copyOf(GPUS), prepared.gpuUuids());
    }

    @Test void rejectsServersWithoutPersistentSystemdUserManager()
    {
        assertThrows(IllegalArgumentException.class, () -> new SshJobSubmissionValidator()
                .validate(submission(), detail(false), devices(true), NOW, UUID.randomUUID()));
    }

    @Test void rejectsStaleOrUnobservableSelectedGpu()
    {
        assertThrows(IllegalArgumentException.class, () -> new SshJobSubmissionValidator()
                .validate(submission(), detail(true), devices(false), NOW, UUID.randomUUID()));
    }

    private static JobSubmission submission()
    {
        return new JobSubmission(7, 3, 0, Set.copyOf(GPUS), Set.of(), "req",
                "/data/hanw/hw_code/gputrain-test", "/usr/bin/bash", List.of("run.sh"), Map.of(),
                3600, 70000, true, 4L, 8L);
    }

    private static ServerDetail detail(boolean persistent)
    {
        Map<String, Boolean> capabilities = Map.of("ssh", true, "gpu", true,
                "systemdUser", true, "persistentUserManager", persistent);
        var server = new ServerView(4L, "node", "host", 22, "SHA256:test", true,
                NOW, NOW, "ONLINE", capabilities);
        var account = new ServerAccountView(8L, 4L, 7L, "alice", "PRIVATE_KEY", true, "/data/hanw");
        return new ServerDetail(server, List.of(account), (ServerSnapshotRecord) null);
    }

    private static List<DeviceView> devices(boolean fresh)
    {
        Instant sampledAt = fresh ? NOW : NOW.minusSeconds(90);
        return GPUS.stream().map(uuid -> new DeviceView(4L, 8L, uuid, "GPU", "OBSERVED", true,
                sampledAt, 80000, 0, 0, "DISABLED", 0)).toList();
    }
}
