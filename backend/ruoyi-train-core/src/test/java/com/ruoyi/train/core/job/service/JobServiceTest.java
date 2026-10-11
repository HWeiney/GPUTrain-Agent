package com.ruoyi.train.core.job.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;

class JobServiceTest
{
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void newJobsRequireAnSshTarget()
    {
        JobService service = new JobService(mapper(null), new ObjectMapper(), null, null, null);
        JobSubmission legacy = new JobSubmission(10L, 1, 0, Set.of(), Set.of(), "new-job",
                "/work", "/usr/bin/python3", List.of("train.py"), Map.of(), 300);
        assertEquals("SSH server and account are required",
                assertThrows(IllegalArgumentException.class, () -> service.submit(legacy, now)).getMessage());
    }

    @Test
    void historicalJobRemainsReadableButCannotBeCanceled()
    {
        JobRecord legacy = new JobRecord(7L, "job-uuid", 10L, 3L, 1, 0,
                "[]", "[]", "{}", "SUCCEEDED", "old-key", 4L, null, now, now);
        JobService service = new JobService(mapper(legacy), new ObjectMapper(), null, null, null);
        assertEquals(7L, service.getOwned(7L, 10L, false).jobId());
        assertThrows(IllegalArgumentException.class, () -> service.getOwned(7L, 11L, false));
        assertEquals("LEGACY_JOB_READ_ONLY",
                assertThrows(IllegalArgumentException.class, () -> service.cancel(7L, now)).getMessage());
    }

    @Test
    void newSshRequestCannotReturnAnUnrelatedHistoricalJob()
    {
        JobRecord legacy = new JobRecord(7L, "job-uuid", 11L, 3L, 1, 0,
                "[]", "[]", "{}", "SUCCEEDED", "shared-key", 4L, null, now, now);
        JobMapper mapper = (JobMapper) Proxy.newProxyInstance(JobMapper.class.getClassLoader(),
                new Class<?>[] { JobMapper.class }, (proxy, method, args) ->
                        "findByRequestKey".equals(method.getName()) ? legacy : null);
        JobService service = new JobService(mapper, new ObjectMapper(), null, null, null);
        JobSubmission request = new JobSubmission(10L, 3, 0, Set.of(), Set.of(), "shared-key",
                "/work", "/usr/bin/python3", List.of("train.py"), Map.of(), 300,
                0, false, 2L, 3L);
        assertThrows(IllegalArgumentException.class, () -> service.submit(request, now));
    }

    private static JobMapper mapper(JobRecord job)
    {
        return (JobMapper) Proxy.newProxyInstance(JobMapper.class.getClassLoader(),
                new Class<?>[] { JobMapper.class }, (proxy, method, args) ->
                        "findById".equals(method.getName()) ? job : null);
    }
}
