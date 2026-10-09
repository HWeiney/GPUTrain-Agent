package com.ruoyi.train.core.server.gpu;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-scoped GPU leases; the database unique key remains the final concurrency guard. */
@Service
public class SshGpuLeaseService
{
    private final SshGpuLeaseMapper mapper;

    public SshGpuLeaseService(SshGpuLeaseMapper mapper) { this.mapper = mapper; }

    @Transactional
    public boolean tryAcquire(long jobId, String jobUuid, long serverId, long accountId,
            List<String> gpuUuids, Instant now)
    {
        if (jobId < 1 || serverId < 1 || accountId < 1 || jobUuid == null || jobUuid.isBlank()
                || now == null || gpuUuids == null || gpuUuids.size() != 3
                || new HashSet<>(gpuUuids).size() != 3
                || gpuUuids.stream().anyMatch(uuid -> uuid == null || !uuid.matches("GPU-[A-Za-z0-9-]{1,120}")))
            throw new IllegalArgumentException("GPU_SELECTION_INVALID");

        for (String gpuUuid : gpuUuids)
            if (mapper.findActiveByServerGpu(serverId, gpuUuid) != null) return false;

        for (String gpuUuid : gpuUuids)
        {
            String idempotencyKey = "ssh-lease:" + jobUuid + ":" + gpuUuid;
            SshGpuLeaseRecord lease = new SshGpuLeaseRecord(null, UUID.randomUUID().toString(), jobId,
                    serverId, accountId, gpuUuid, "ACTIVE", idempotencyKey, now,
                    now.plusSeconds(3600), now, null, 0, now, now);
            if (mapper.insert(lease) != 1) throw new IllegalStateException("Unable to acquire SSH GPU lease");
        }
        return true;
    }

    @Transactional
    public void releaseForJob(long jobId, Instant now)
    {
        for (SshGpuLeaseRecord lease : mapper.findByJob(jobId))
            if ("ACTIVE".equals(lease.status()) || "QUARANTINED".equals(lease.status()))
                mapper.release(lease.leaseId(), now);
    }
}
