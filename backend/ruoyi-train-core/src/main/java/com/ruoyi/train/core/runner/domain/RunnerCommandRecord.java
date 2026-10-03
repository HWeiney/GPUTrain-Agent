package com.ruoyi.train.core.runner.domain;

import java.time.Instant;

public record RunnerCommandRecord(Long commandId, String commandUuid, Long runnerId,
        String commandType, String payload, String idempotencyKey,
        String status, Instant leaseUntil, int attemptCount, String result,
        String errorCode, Instant createdAt, Instant updatedAt)
{
}
