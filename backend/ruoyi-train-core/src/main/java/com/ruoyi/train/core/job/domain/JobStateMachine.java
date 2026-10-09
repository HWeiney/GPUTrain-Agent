package com.ruoyi.train.core.job.domain;

import java.util.EnumSet;
import java.util.Set;

public final class JobStateMachine
{
    private static final Set<JobStatus> NO_TRANSITIONS = EnumSet.noneOf(JobStatus.class);

    private JobStateMachine() { }

    public static void requireTransition(JobStatus from, JobStatus to)
    {
        if (!allowed(from).contains(to))
            throw new IllegalStateException("Illegal job transition: " + from + " -> " + to);
    }

    private static Set<JobStatus> allowed(JobStatus from)
    {
        return switch (from)
        {
            case PENDING -> EnumSet.of(JobStatus.QUEUED, JobStatus.CANCELED, JobStatus.QUARANTINED);
            case QUEUED -> EnumSet.of(JobStatus.ALLOCATING, JobStatus.CANCELED, JobStatus.QUARANTINED);
            case ALLOCATING -> EnumSet.of(JobStatus.ALLOCATED, JobStatus.QUEUED, JobStatus.FAILED,
                    JobStatus.QUARANTINED);
            case ALLOCATED -> EnumSet.of(JobStatus.STARTING, JobStatus.FAILED, JobStatus.CANCELED, JobStatus.QUARANTINED);
            case STARTING -> EnumSet.of(JobStatus.RUNNING, JobStatus.SUCCEEDED,
                    JobStatus.FAILED, JobStatus.STOPPING, JobStatus.STOPPED, JobStatus.RECONCILING,
                    JobStatus.UNKNOWN, JobStatus.QUARANTINED);
            case RUNNING -> EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.STOPPING,
                    JobStatus.RECONCILING, JobStatus.UNKNOWN, JobStatus.QUARANTINED);
            case STOPPING -> EnumSet.of(JobStatus.STOPPED, JobStatus.FAILED, JobStatus.RUNNING,
                    JobStatus.RECONCILING, JobStatus.UNKNOWN, JobStatus.QUARANTINED);
            case UNKNOWN -> EnumSet.of(JobStatus.RECONCILING, JobStatus.STOPPING, JobStatus.QUARANTINED);
            case RECONCILING -> EnumSet.of(JobStatus.RUNNING, JobStatus.STOPPING, JobStatus.SUCCEEDED,
                    JobStatus.FAILED, JobStatus.STOPPED, JobStatus.UNKNOWN, JobStatus.QUARANTINED);
            case QUARANTINED -> EnumSet.of(JobStatus.RUNNING, JobStatus.FAILED, JobStatus.STOPPED,
                    JobStatus.STOPPING, JobStatus.RECONCILING, JobStatus.QUARANTINED);
            case SUCCEEDED, FAILED, STOPPED, CANCELED -> NO_TRANSITIONS;
        };
    }
}
