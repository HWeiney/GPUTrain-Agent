package com.ruoyi.train.runner.protocol;

import java.util.Objects;

public record RunnerCredentials(String runnerId, String runnerName, String secret)
{
    public RunnerCredentials
    {
        Objects.requireNonNull(runnerId, "runnerId");
        Objects.requireNonNull(runnerName, "runnerName");
        Objects.requireNonNull(secret, "secret");
    }

    @Override
    public String toString()
    {
        return "RunnerCredentials[runnerId=" + runnerId + "]";
    }
}
