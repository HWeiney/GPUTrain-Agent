package com.ruoyi.train.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.gpu.service.GpuLeaseService;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.observability.JobObservabilityService;

class McpQueryServiceTest
{
    @Test
    void twoUsersCannotReadEachOthersJobsEvenWhenCallerIsAdmin()
    {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.findById(77)).thenReturn(job(77, 200));
        JobService jobs = jobs(mapper);
        McpQueryService queries = new McpQueryService(jobs, mock(JobObservabilityService.class));

        assertThrows(IllegalArgumentException.class, () -> queries.getMyExperiment(100, 77));
        assertThrows(IllegalArgumentException.class, () -> queries.getMyExperiment(1, 77));
        assertEquals(77L, queries.getMyExperiment(200, 77).get("jobId"));
        verify(mapper, atLeastOnce()).findById(77);
    }

    @Test
    void listIsScopedAndDoesNotExposeExecutionSecrets()
    {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.listByOwner(100L, false, 100, null)).thenReturn(List.of(job(9, 100)));
        JobService jobs = jobs(mapper);
        McpQueryService queries = new McpQueryService(jobs, mock(JobObservabilityService.class));

        var result = queries.listMyExperiments(100);

        assertEquals(9L, result.get(0).get("jobId"));
        assertFalse(result.get(0).containsKey("processSpecJson"));
        verify(mapper).listByOwner(100L, false, 100, null);
    }

    @Test
    void logsAreOwnerScopedAndBounded()
    {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.findById(12)).thenReturn(job(12, 100));
        JobObservabilityService observability = mock(JobObservabilityService.class);
        JobService jobs = jobs(mapper);
        McpQueryService queries = new McpQueryService(jobs, observability);

        queries.logs(100, 12, 5, 10000);

        verify(observability).logs(12, 5, 100);
        assertThrows(IllegalArgumentException.class, () -> queries.logs(101, 12, 0, 20));
    }

    private static JobRecord job(long id, long owner)
    {
        return new JobRecord(id, "job-" + id, owner, 1L, 1, 1024,
                "[\"allowed-secret\"]", "[]", "{\"command\":\"secret\"}",
                "RUNNING", "request-secret", 2L, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static JobService jobs(JobMapper mapper)
    {
        return new JobService(mapper, mock(GpuLeaseService.class), mock(RunnerCommandService.class),
                mock(RunnerCommandMapper.class), new com.fasterxml.jackson.databind.ObjectMapper());
    }
}
