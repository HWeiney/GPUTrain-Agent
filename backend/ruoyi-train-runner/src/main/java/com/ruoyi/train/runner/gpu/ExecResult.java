package com.ruoyi.train.runner.gpu;

import java.util.Objects;

/** Result of a completed command invocation. */
public record ExecResult(int exitCode, String stdout, String stderr)
{
    public ExecResult
    {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }
}
