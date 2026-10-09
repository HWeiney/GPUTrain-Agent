package com.ruoyi.train.core.server.gpu;

import java.time.Instant;

public record SshGpuLeaseRecord(Long leaseId, String leaseUuid, Long jobId,
        Long serverId, Long accountId, String gpuUuid, String status,
        String idempotencyKey, Instant acquiredAt, Instant expiresAt,
        Instant renewedAt, Instant releasedAt, int attempt,
        Instant createdAt, Instant updatedAt)
{
}
