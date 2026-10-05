package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

public record GpuAuditRecord(Long auditId, Instant occurredAt, Long actorUserId,
        Long jobId, Long runnerId, String gpuUuid, Long leaseId,
        String action, String decision, String reasonCode, String detail)
{
}
