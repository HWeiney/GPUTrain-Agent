package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

public record GpuLeaseRecord(Long leaseId, String leaseUuid, Long jobId,
        Long runnerId, String gpuUuid, String status, String idempotencyKey,
        Instant acquiredAt, Instant expiresAt, Instant renewedAt,
        Instant releasedAt, int attempt, Instant createdAt, Instant updatedAt)
{
}
