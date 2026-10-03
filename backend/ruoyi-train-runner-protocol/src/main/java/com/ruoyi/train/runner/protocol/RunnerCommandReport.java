package com.ruoyi.train.runner.protocol;

import java.time.Instant;
import java.util.Objects;

public record RunnerCommandReport(RunnerCommandStatus previousStatus,
        RunnerCommandStatus status, Long pid, Instant processStartedAt,
        Integer exitCode, RunnerErrorCode errorCode, String errorMessage)
{
    public RunnerCommandReport
    {
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(status, "status");
    }
}
