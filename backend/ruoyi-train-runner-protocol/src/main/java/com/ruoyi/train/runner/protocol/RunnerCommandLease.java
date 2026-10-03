package com.ruoyi.train.runner.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RunnerCommandLease(UUID commandId, RunnerCommandType type,
        ProcessSpec process, UUID targetCommandId, Instant leaseUntil)
{
    public RunnerCommandLease
    {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (type == RunnerCommandType.START_PROCESS && (process == null || targetCommandId != null))
            throw new IllegalArgumentException("START_PROCESS requires process and no targetCommandId");
        if (type == RunnerCommandType.STOP_PROCESS && (targetCommandId == null || process != null))
            throw new IllegalArgumentException("STOP_PROCESS requires targetCommandId and no process");
    }
}
