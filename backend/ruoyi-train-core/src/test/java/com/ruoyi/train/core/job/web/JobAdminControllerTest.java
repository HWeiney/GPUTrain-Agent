package com.ruoyi.train.core.job.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.job.service.JobSubmission;

class JobAdminControllerTest
{
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private JobRecord job(long ownerUserId)
    {
        return new JobRecord(7L, "job-uuid", ownerUserId, 1L, 1, 8000L,
                "[]", "[]", "{}", "RUNNING", "req-key", null, null, NOW, NOW);
    }

    private JobService serviceReturning(JobRecord job)
    {
        com.ruoyi.train.core.job.mapper.JobMapper mapper = proxy(job);
        return new JobService(mapper,
                new com.ruoyi.train.core.gpu.service.GpuLeaseService(
                        noop(com.ruoyi.train.core.gpu.mapper.GpuLeaseMapper.class),
                        new com.ruoyi.train.core.gpu.service.GpuCandidateSelector()),
                new com.ruoyi.train.core.runner.service.RunnerCommandService(
                        noop(com.ruoyi.train.core.runner.mapper.RunnerCommandMapper.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                noop(com.ruoyi.train.core.runner.mapper.RunnerCommandMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static <T> T noop(Class<T> type)
    {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] { type }, (p, m, a) ->
                {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static com.ruoyi.train.core.job.mapper.JobMapper proxy(JobRecord job)
    {
        return (com.ruoyi.train.core.job.mapper.JobMapper) java.lang.reflect.Proxy.newProxyInstance(
                com.ruoyi.train.core.job.mapper.JobMapper.class.getClassLoader(),
                new Class<?>[] { com.ruoyi.train.core.job.mapper.JobMapper.class },
                (p, m, a) -> "findById".equals(m.getName()) ? job : null);
    }

    @Test
    void ownerCanReadOwnJob()
    {
        JobService service = serviceReturning(job(10L));
        assertEquals(7L, service.getOwned(7L, 10L, false).jobId());
    }

    @Test
    void nonOwnerCannotReadAnotherUsersJob()
    {
        JobService service = serviceReturning(job(10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.getOwned(7L, 11L, false));
    }

    @Test
    void adminCanReadAnyJob()
    {
        JobService service = serviceReturning(job(10L));
        assertEquals(7L, service.getOwned(7L, 1L, true).jobId());
    }

    @Test
    void createRequiresManagePermission() throws NoSuchMethodException
    {
        Method endpoint = JobAdminController.class.getMethod("create",
                JobAdminController.CreateJobRequest.class);
        PreAuthorize auth = endpoint.getAnnotation(PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("@ss.hasPermi('train:job:manage')", auth.value());
    }

    @Test
    void viewRequiresViewPermission() throws NoSuchMethodException
    {
        Method endpoint = JobAdminController.class.getMethod("get", long.class);
        PreAuthorize auth = endpoint.getAnnotation(PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("@ss.hasPermi('train:job:view')", auth.value());
    }

    @Test
    void stopRequiresManagePermission() throws NoSuchMethodException
    {
        Method endpoint = JobAdminController.class.getMethod("stop", long.class);
        PreAuthorize auth = endpoint.getAnnotation(PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("@ss.hasPermi('train:job:manage')", auth.value());
    }

    @Test
    void cancelRequiresManagePermission() throws NoSuchMethodException
    {
        Method endpoint = JobAdminController.class.getMethod("cancel", long.class);
        PreAuthorize auth = endpoint.getAnnotation(PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("@ss.hasPermi('train:job:manage')", auth.value());
    }

    @Test
    void createSubmissionCanRestrictTheGpuAllowlist()
    {
        Set<String> selected = Set.of("GPU-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "GPU-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "GPU-cccccccc-cccc-cccc-cccc-cccccccccccc");
        JobSubmission submission = JobAdminController.toSubmission(42L,
                new JobAdminController.CreateJobRequest("docowl2-srfund", 1, "gpu456-smoke", selected));

        assertEquals(selected, submission.allowedUuids());
        assertEquals(3, submission.gpuCount());
    }

    @Test
    void createSubmissionBindsTheSelectedServerAndAccount()
    {
        Set<String> selected = Set.of("GPU-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "GPU-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "GPU-cccccccc-cccc-cccc-cccc-cccccccccccc");
        JobSubmission submission = JobAdminController.toSubmission(42L,
                new JobAdminController.CreateJobRequest("docowl2-srfund", 1, "req-ssh", 8L, 12L, selected));

        assertEquals(8L, submission.serverId());
        assertEquals(12L, submission.accountId());
        assertEquals("/data/hanw/hw_code/gputrain-test", submission.workingDirectory());
    }

    @Test
    void createSubmissionRejectsAnIncompleteGpuAllowlist()
    {
        assertThrows(IllegalArgumentException.class, () -> JobAdminController.toSubmission(42L,
                new JobAdminController.CreateJobRequest("docowl2-srfund", 1, "gpu456-smoke",
                        Set.of("GPU-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))));
    }

    @Test
    void createSubmissionRequiresExplicitGpuSelection()
    {
        assertThrows(IllegalArgumentException.class, () -> JobAdminController.toSubmission(42L,
                new JobAdminController.CreateJobRequest("docowl2-srfund", 1, "gpu456-smoke", Set.of())));
    }
}
