package com.ruoyi.train.core.job.domain;

import java.time.Instant;

public record JobRecord(Long jobId, String jobUuid, Long ownerUserId,
        Long runnerId, int gpuCount, long minMemoryMiB,
        String allowedGpuUuidsJson, String deniedGpuUuidsJson,
        String processSpecJson,
        String status, String requestKey, Long startCommandId,
        Long stopCommandId, Instant createdAt, Instant updatedAt,
        String executionMode, Long serverId, Long accountId,
        String remoteUnitName, String remoteWorkingDirectory)
{
    /** Keeps source compatibility for Runner-era callers while new SSH submissions opt in explicitly. */
    public JobRecord(Long jobId, String jobUuid, Long ownerUserId,
            Long runnerId, int gpuCount, long minMemoryMiB,
            String allowedGpuUuidsJson, String deniedGpuUuidsJson,
            String processSpecJson, String status, String requestKey,
            Long startCommandId, Long stopCommandId, Instant createdAt, Instant updatedAt)
    {
        this(jobId, jobUuid, ownerUserId, runnerId, gpuCount, minMemoryMiB,
                allowedGpuUuidsJson, deniedGpuUuidsJson, processSpecJson, status,
                requestKey, startCommandId, stopCommandId, createdAt, updatedAt,
                "RUNNER_LEGACY", null, null, null, null);
    }
}
