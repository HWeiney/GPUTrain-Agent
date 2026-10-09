package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.job.domain.JobStatus;

class SshJobDispatcherTest
{
    @Test void mapsOnlyVerifiedRemoteStatesToTerminalJobStates()
    {
        assertEquals(JobStatus.RUNNING, SshJobDispatcher.status(state("loaded", "active", "success", 0)));
        assertEquals(JobStatus.SUCCEEDED, SshJobDispatcher.status(state("loaded", "inactive", "success", 0)));
        assertEquals(JobStatus.FAILED, SshJobDispatcher.status(state("loaded", "failed", "exit-code", 1)));
        assertEquals(JobStatus.UNKNOWN, SshJobDispatcher.status(state("not-found", "inactive", "success", 0)));
    }

    private static SystemdUnitState state(String load, String active, String result, int exit)
    { return new SystemdUnitState(load, active, "dead", result, 0L, exit, ""); }
}
