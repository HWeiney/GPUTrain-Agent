package com.ruoyi.train.core.job.domain;

import java.time.Instant;

public record JobRecord(Long jobId, String jobUuid, Long ownerUserId,
        Long runnerId, int gpuCount, long minMemoryMiB,
        String allowedGpuUuidsJson, String deniedGpuUuidsJson,
        String status, String requestKey, Long startCommandId,
        Long stopCommandId, Instant createdAt, Instant updatedAt)
{
}
