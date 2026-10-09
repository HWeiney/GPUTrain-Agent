package com.ruoyi.train.core.server.gpu;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SshGpuLeaseServiceTest
{
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final List<String> GPUS = List.of("GPU-a", "GPU-b", "GPU-c");

    @Test void acquiresThreeServerScopedGpuLeasesTogether()
    {
        var mapper = new MemoryMapper();
        var service = new SshGpuLeaseService(mapper);
        assertTrue(service.tryAcquire(7, "job-uuid", 4, 8, GPUS, NOW));
        assertEquals(3, mapper.active.size());
        assertTrue(mapper.active.values().stream().allMatch(lease -> lease.serverId() == 4L
                && lease.accountId() == 8L && lease.status().equals("ACTIVE")));
    }

    @Test void rejectsExistingLeaseWithoutCreatingPartialLeases()
    {
        var mapper = new MemoryMapper();
        mapper.active.put("4:GPU-b", lease("GPU-b"));
        assertFalse(new SshGpuLeaseService(mapper).tryAcquire(7, "job-uuid", 4, 8, GPUS, NOW));
        assertEquals(Set.of("4:GPU-b"), mapper.active.keySet());
    }

    @Test void rejectsDuplicateOrIncompleteGpuSelection()
    {
        var service = new SshGpuLeaseService(new MemoryMapper());
        assertThrows(IllegalArgumentException.class, () -> service.tryAcquire(7, "job", 4, 8,
                List.of("GPU-a", "GPU-a", "GPU-c"), NOW));
        assertThrows(IllegalArgumentException.class, () -> service.tryAcquire(7, "job", 4, 8,
                List.of("GPU-a", "GPU-b"), NOW));
    }

    private static SshGpuLeaseRecord lease(String gpu)
    { return new SshGpuLeaseRecord(1L, UUID.randomUUID().toString(), 5L, 4L, 8L, gpu, "ACTIVE", "existing", NOW, NOW.plusSeconds(300), NOW, null, 0, NOW, NOW); }

    private static final class MemoryMapper implements SshGpuLeaseMapper
    {
        final Map<String, SshGpuLeaseRecord> active = new HashMap<>();
        @Override public int insert(SshGpuLeaseRecord lease)
        { active.put(lease.serverId() + ":" + lease.gpuUuid(), lease); return 1; }
        @Override public List<SshGpuLeaseRecord> findByJob(long jobId)
        { return active.values().stream().filter(lease -> lease.jobId() == jobId).toList(); }
        @Override public SshGpuLeaseRecord findActiveByServerGpu(long serverId, String gpuUuid)
        { return active.get(serverId + ":" + gpuUuid); }
        @Override public int release(long leaseId, Instant releasedAt) { return 1; }
    }
}
