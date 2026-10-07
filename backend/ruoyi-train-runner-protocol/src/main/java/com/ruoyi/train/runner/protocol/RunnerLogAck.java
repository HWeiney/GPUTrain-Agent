package com.ruoyi.train.runner.protocol;

public record RunnerLogAck(long nextOffset, boolean accepted)
{
    public RunnerLogAck { if (nextOffset < 0) throw new IllegalArgumentException("nextOffset must be non-negative"); }
}
