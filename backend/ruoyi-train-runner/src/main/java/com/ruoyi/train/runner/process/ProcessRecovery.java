package com.ruoyi.train.runner.process;

import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProcessStatus;

public record ProcessRecovery(RunnerProcessStatus status, RunnerErrorCode errorCode) { }
