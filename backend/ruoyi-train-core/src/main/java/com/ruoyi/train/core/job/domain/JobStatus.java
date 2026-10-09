package com.ruoyi.train.core.job.domain;

public enum JobStatus
{
    PENDING, QUEUED, ALLOCATING, ALLOCATED, STARTING, RUNNING, RECONCILING, UNKNOWN,
    SUCCEEDED, FAILED, STOPPING, STOPPED, QUARANTINED, CANCELED;

    public boolean isTerminal()
    {
        return this == SUCCEEDED || this == FAILED || this == STOPPED || this == CANCELED;
    }
}
