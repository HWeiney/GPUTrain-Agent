package com.ruoyi.train.core.job.domain;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JobStateMachineTest
{
    @ParameterizedTest
    @MethodSource("legalTransitions")
    void allowsLegalTransitions(JobStatus from, JobStatus to)
    {
        JobStateMachine.requireTransition(from, to);
    }

    @Test
    void terminalStatesAreTerminal()
    {
        assertTrue(JobStatus.SUCCEEDED.isTerminal());
        assertTrue(JobStatus.FAILED.isTerminal());
        assertTrue(JobStatus.STOPPED.isTerminal());
        assertTrue(JobStatus.CANCELED.isTerminal());
    }

    @ParameterizedTest
    @EnumSource(names = {"SUCCEEDED", "FAILED", "STOPPED", "CANCELED"})
    void terminalStatesCannotAdvance(JobStatus terminal)
    {
        for (JobStatus next : JobStatus.values())
        {
            assertThrows(IllegalStateException.class,
                    () -> JobStateMachine.requireTransition(terminal, next));
        }
    }

    @Test
    void anyNonTerminalCanQuarantine()
    {
        for (JobStatus status : JobStatus.values())
        {
            if (!status.isTerminal())
                JobStateMachine.requireTransition(status, JobStatus.QUARANTINED);
        }
    }

    @Test
    void rejectsIllegalTransition()
    {
        assertThrows(IllegalStateException.class,
                () -> JobStateMachine.requireTransition(JobStatus.PENDING, JobStatus.RUNNING));
        assertThrows(IllegalStateException.class,
                () -> JobStateMachine.requireTransition(JobStatus.QUEUED, JobStatus.SUCCEEDED));
    }

    @Test
    void quarantineCanRecoverToRunningOrFail()
    {
        JobStateMachine.requireTransition(JobStatus.QUARANTINED, JobStatus.RUNNING);
        JobStateMachine.requireTransition(JobStatus.QUARANTINED, JobStatus.FAILED);
    }

    @Test
    void uncertainRemoteStatesAreNonTerminalAndMustReconcile()
    {
        assertFalse(JobStatus.UNKNOWN.isTerminal());
        assertFalse(JobStatus.RECONCILING.isTerminal());
        JobStateMachine.requireTransition(JobStatus.STARTING, JobStatus.UNKNOWN);
        JobStateMachine.requireTransition(JobStatus.UNKNOWN, JobStatus.RECONCILING);
        JobStateMachine.requireTransition(JobStatus.RECONCILING, JobStatus.RUNNING);
        assertThrows(IllegalStateException.class, () -> JobStateMachine.requireTransition(JobStatus.UNKNOWN, JobStatus.SUCCEEDED));
    }

    @Test
    void runningCanStopThenStopped()
    {
        JobStateMachine.requireTransition(JobStatus.RUNNING, JobStatus.STOPPING);
        JobStateMachine.requireTransition(JobStatus.STOPPING, JobStatus.STOPPED);
        assertFalse(JobStatus.RUNNING.isTerminal());
        assertEquals(JobStatus.QUARANTINED, JobStatus.QUARANTINED);
    }

    static Stream<Arguments> legalTransitions()
    {
        return Stream.of(
                Arguments.of(JobStatus.PENDING, JobStatus.QUEUED),
                Arguments.of(JobStatus.PENDING, JobStatus.CANCELED),
                Arguments.of(JobStatus.QUEUED, JobStatus.ALLOCATING),
                Arguments.of(JobStatus.QUEUED, JobStatus.CANCELED),
                Arguments.of(JobStatus.ALLOCATING, JobStatus.ALLOCATED),
                Arguments.of(JobStatus.ALLOCATING, JobStatus.FAILED),
                Arguments.of(JobStatus.ALLOCATING, JobStatus.QUEUED),
                Arguments.of(JobStatus.ALLOCATED, JobStatus.STARTING),
                Arguments.of(JobStatus.ALLOCATED, JobStatus.FAILED),
                Arguments.of(JobStatus.STARTING, JobStatus.RUNNING),
                Arguments.of(JobStatus.STARTING, JobStatus.FAILED),
                Arguments.of(JobStatus.RUNNING, JobStatus.SUCCEEDED),
                Arguments.of(JobStatus.RUNNING, JobStatus.FAILED),
                Arguments.of(JobStatus.RUNNING, JobStatus.STOPPING),
                Arguments.of(JobStatus.STOPPING, JobStatus.STOPPED),
                Arguments.of(JobStatus.STOPPING, JobStatus.FAILED),
                Arguments.of(JobStatus.STOPPING, JobStatus.RUNNING),
                Arguments.of(JobStatus.QUARANTINED, JobStatus.RUNNING),
                Arguments.of(JobStatus.QUARANTINED, JobStatus.FAILED),
                Arguments.of(JobStatus.QUARANTINED, JobStatus.STOPPED));
    }
}
