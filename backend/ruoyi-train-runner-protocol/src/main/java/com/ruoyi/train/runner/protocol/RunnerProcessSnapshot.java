package com.ruoyi.train.runner.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RunnerProcessSnapshot(UUID commandId, long pid, Instant processStartedAt,
        String executable, String argumentsSha256, RunnerProcessStatus status,
        Integer exitCode, RunnerErrorCode errorCode)
{
    public RunnerProcessSnapshot
    {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(processStartedAt, "processStartedAt");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(argumentsSha256, "argumentsSha256");
        Objects.requireNonNull(status, "status");
    }
}
