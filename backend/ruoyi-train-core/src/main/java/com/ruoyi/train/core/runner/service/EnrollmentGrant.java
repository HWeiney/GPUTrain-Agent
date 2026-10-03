package com.ruoyi.train.core.runner.service;

import java.time.Instant;

public record EnrollmentGrant(long enrollmentId, String plaintextToken, String runnerName,
        String linuxUsername, String allowedRoot, Instant expiresAt)
{
    @Override
    public String toString()
    {
        return "EnrollmentGrant[enrollmentId=" + enrollmentId + ", runnerName=" + runnerName + "]";
    }
}
