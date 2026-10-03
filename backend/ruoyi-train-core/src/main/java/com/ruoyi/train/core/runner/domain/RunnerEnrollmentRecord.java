package com.ruoyi.train.core.runner.domain;

import java.time.Instant;

public record RunnerEnrollmentRecord(Long enrollmentId, Long ownerUserId,
        String runnerName, String linuxUsername, String allowedRoot,
        String tokenDigest, String tokenCiphertext, String idempotencyKey,
        Instant expiresAt, Instant consumedAt, Instant createdAt)
{
    @Override
    public String toString()
    {
        return "RunnerEnrollmentRecord[enrollmentId=" + enrollmentId + "]";
    }
}
