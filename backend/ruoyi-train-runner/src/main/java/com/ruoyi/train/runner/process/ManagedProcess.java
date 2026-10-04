package com.ruoyi.train.runner.process;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.ruoyi.train.runner.protocol.RunnerProcessStatus;

/** Durable identity and last observed state of a locally started process. */
public record ManagedProcess(UUID commandId, String idempotencyKey, long pid, Instant processStartedAt,
        String workingDirectory, String executable, String argumentsSha256, String stdoutPath, String stderrPath,
        RunnerProcessStatus status, Integer exitCode)
{
    public ManagedProcess
    {
        Objects.requireNonNull(commandId); Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(processStartedAt); Objects.requireNonNull(workingDirectory);
        Objects.requireNonNull(executable); Objects.requireNonNull(argumentsSha256);
        Objects.requireNonNull(stdoutPath); Objects.requireNonNull(stderrPath); Objects.requireNonNull(status);
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
    }

    public ManagedProcess withStatus(RunnerProcessStatus next, Integer code)
    { return new ManagedProcess(commandId, idempotencyKey, pid, processStartedAt, workingDirectory,
            executable, argumentsSha256, stdoutPath, stderrPath, next, code); }
}
