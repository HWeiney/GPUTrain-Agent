package com.ruoyi.train.runner.protocol;

import java.util.Objects;

public record RunnerRegistration(long enrollmentId, String enrollmentToken,
        String runnerName, String linuxUsername)
{
    public RunnerRegistration
    {
        Objects.requireNonNull(enrollmentToken, "enrollmentToken");
        Objects.requireNonNull(runnerName, "runnerName");
        Objects.requireNonNull(linuxUsername, "linuxUsername");
    }

    @Override
    public String toString()
    {
        return "RunnerRegistration[enrollmentId=" + enrollmentId + ", runnerName=" + runnerName
            + ", linuxUsername=" + linuxUsername + "]";
    }
}
