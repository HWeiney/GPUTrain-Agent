package com.ruoyi.train.runner.gpu;

import java.time.Duration;
import java.util.List;

/** Abstraction over a single bounded command execution; argv is discrete, never a shell string. */
public interface CommandExecutor
{
    ExecResult run(List<String> argv, Duration timeout, int maxOutputBytes)
            throws java.io.IOException;
}
