package com.ruoyi.train.core.runner.domain;

import java.time.Instant;

public record RunnerRecord(Long runnerId, Long ownerUserId, String runnerName, String linuxUsername,
        String allowedRoot, String secretCiphertext, String status,
        Instant lastSeenAt, Instant createdAt, Instant updatedAt)
{
    @Override
    public String toString()
    {
        return "RunnerRecord[runnerId=" + runnerId + ", runnerName=" + runnerName + "]";
    }
}
