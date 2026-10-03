package com.ruoyi.train.core.runner.domain;

import java.time.Instant;

public record RunnerProcessRecord(Long processId, Long commandId, Long runnerId,
        long pid, Instant processStartedAt, String executable,
        String argumentsSha256, String status, Integer exitCode,
        String errorCode, Instant lastSeenAt, Instant createdAt, Instant updatedAt)
{
}
