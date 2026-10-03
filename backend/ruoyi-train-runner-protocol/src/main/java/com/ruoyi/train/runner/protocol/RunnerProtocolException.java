package com.ruoyi.train.runner.protocol;

import java.util.Objects;

public final class RunnerProtocolException extends RuntimeException
{
    private final RunnerErrorCode code;

    public RunnerProtocolException(RunnerErrorCode code, String message)
    {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public RunnerErrorCode code()
    {
        return code;
    }
}
