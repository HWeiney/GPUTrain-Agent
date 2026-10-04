package com.ruoyi.train.runner.process;

import com.ruoyi.train.runner.protocol.RunnerErrorCode;

public final class ProcessPolicyException extends RuntimeException
{
    private final RunnerErrorCode code;
    public ProcessPolicyException(RunnerErrorCode code, String message) { super(message); this.code = code; }
    public RunnerErrorCode code() { return code; }
}
