package com.ruoyi.train.core.job.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.gpu.domain.GpuLeaseRecord;
import com.ruoyi.train.core.gpu.service.GpuLeaseService;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.domain.JobStatus;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.runner.protocol.ProcessSpec;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

class JobServiceTest
{
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final InMemoryJobMapper jobMapper = new InMemoryJobMapper();
    private final InMemoryCommandMapper commandMapper = new InMemoryCommandMapper();
    private final FakeLeaseService leaseService = new FakeLeaseService();
    private final FakeCommandService commandService = new FakeCommandService(commandMapper);
    private final JobService service = new JobService(jobMapper, leaseService,
            commandService, commandMapper, new ObjectMapper());

    private JobSubmission submission(String requestKey, int gpuCount)
    {
        return new JobSubmission(10L, gpuCount, 8000L, null, null, requestKey,
                "/data/fixtures", "python3", List.of("train.py", "--epochs", "1"),
                Map.of(), 300L);
    }

    @Test
    void submitAllocatesLeasesAndCreatesStartCommand()
    {
        JobRecord job = service.submit(submission("req-1", 1), NOW);
        assertNotNull(job.jobId());
        assertEquals(JobStatus.STARTING.name(), job.status());
        assertEquals(Long.valueOf(1L), job.runnerId());
        assertNotNull(job.startCommandId());
        assertEquals(1, leaseService.allocateCalls);
        assertEquals(10L, leaseService.lastOwnerUserId);
        assertEquals(1, commandService.createCalls);
        assertEquals("gpu-0", commandService.lastProcessSpec.environment().get("CUDA_VISIBLE_DEVICES"));
    }

    @Test
    void duplicateSubmissionReturnsSameJobWithoutSecondProcess()
    {
        JobRecord first = service.submit(submission("req-2", 1), NOW);
        JobRecord again = service.submit(submission("req-2", 1), NOW);
        assertSame(first, again);
        assertEquals(1, leaseService.allocateCalls);
        assertEquals(1, commandService.createCalls);
    }

    @Test
    void syncRunningAdvancesJobAndRenewsLeases()
    {
        JobRecord job = service.submit(submission("req-3", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        JobRecord running = service.sync(job.jobId(), NOW);
        assertEquals(JobStatus.RUNNING.name(), running.status());
        assertEquals(1, leaseService.renewCalls);
    }

    @Test
    void syncSucceededReleasesLeases()
    {
        JobRecord job = service.submit(submission("req-4", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "SUCCEEDED");
        JobRecord done = service.sync(job.jobId(), NOW);
        assertEquals(JobStatus.SUCCEEDED.name(), done.status());
        assertEquals(1, leaseService.releaseCalls);
    }

    @Test
    void syncFailedReleasesLeases()
    {
        JobRecord job = service.submit(submission("req-5", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "FAILED");
        JobRecord done = service.sync(job.jobId(), NOW);
        assertEquals(JobStatus.FAILED.name(), done.status());
        assertEquals(1, leaseService.releaseCalls);
    }

    @Test
    void stopCreatesStopCommandAndMovesToStopping()
    {
        JobRecord job = service.submit(submission("req-6", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        service.sync(job.jobId(), NOW);
        JobRecord stopping = service.stop(job.jobId(), NOW);
        assertEquals(JobStatus.STOPPING.name(), stopping.status());
        assertNotNull(stopping.stopCommandId());
        assertEquals(1, commandService.stopCalls);

        commandMapper.setStatus(stopping.stopCommandId(), "STOPPED");
        service.syncCommand(java.util.UUID.fromString(
                commandMapper.findById(stopping.stopCommandId()).commandUuid()), NOW);
        JobRecord stopped = jobMapper.findById(job.jobId());
        assertEquals(JobStatus.STOPPED.name(), stopped.status());
        assertEquals(1, leaseService.releaseCalls);
    }

    @Test
    void runningStartReportDoesNotUndoAnInFlightStop()
    {
        JobRecord job = service.submit(submission("req-stop-race", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        service.sync(job.jobId(), NOW);
        JobRecord stopping = service.stop(job.jobId(), NOW);
        commandMapper.setStatus(stopping.stopCommandId(), "SUCCEEDED");

        assertEquals(JobStatus.STOPPING.name(), service.sync(job.jobId(), NOW).status());
        assertEquals(0, leaseService.releaseCalls);

        commandMapper.setStatus(job.startCommandId(), "STOPPED");
        assertEquals(JobStatus.STOPPED.name(), service.sync(job.jobId(), NOW).status());
        assertEquals(1, leaseService.releaseCalls);
    }

    @Test
    void failedStopQuarantinesJobAndKeepsGpuLease()
    {
        JobRecord job = service.submit(submission("req-stop-failed", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        service.sync(job.jobId(), NOW);
        JobRecord stopping = service.stop(job.jobId(), NOW);
        commandMapper.setStatus(stopping.stopCommandId(), "FAILED");

        JobRecord result = service.sync(job.jobId(), NOW);

        assertEquals(JobStatus.QUARANTINED.name(), result.status());
        assertEquals(0, leaseService.releaseCalls);
        assertFalse(leaseService.active.isEmpty());
    }

    @Test
    void runnerCommandReportSynchronizesAssociatedJobAndReleasesLease()
    {
        JobRecord job = service.submit(submission("req-command-sync", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "SUCCEEDED");

        service.syncCommand(java.util.UUID.fromString(
                commandMapper.findById(job.startCommandId()).commandUuid()), NOW);

        assertEquals(JobStatus.SUCCEEDED.name(), jobMapper.findById(job.jobId()).status());
        assertEquals(1, leaseService.releaseCalls);
    }

    @Test
    void clientCannotOverrideSchedulerGpuBinding()
    {
        JobSubmission invalid = new JobSubmission(10L, 1, 8000L, null, null, "req-env",
                "/data/fixtures", "python3", List.of("train.py"),
                Map.of("cuda_visible_devices", "0"), 300L);

        assertThrows(IllegalArgumentException.class, () -> service.submit(invalid, NOW));
        assertEquals(0, leaseService.allocateCalls);
        assertEquals(0, commandService.createCalls);
    }

    @Test
    void repeatedStopIsIdempotent()
    {
        JobRecord job = service.submit(submission("req-7", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        service.sync(job.jobId(), NOW);
        service.stop(job.jobId(), NOW);
        service.stop(job.jobId(), NOW);
        assertEquals(1, commandService.stopCalls);
    }

    @Test
    void queuedJobCanBeCanceledWithoutIssuingRunnerCommand()
    {
        JobRecord queued = new JobRecord(null, "queued-uuid", 10L, null, 3, 0L,
                "[]", "[]", "{}", "QUEUED", "req-queued", null, null, NOW, NOW);
        jobMapper.insert(queued);
        long id = jobMapper.findByRequestKey("req-queued").jobId();

        assertEquals("CANCELED", service.cancel(id, NOW).status());
        assertEquals("CANCELED", service.cancel(id, NOW).status());
        assertEquals(0, commandService.stopCalls);
    }

    @Test
    void allocatedJobCannotBeCanceledAsQueued()
    {
        JobRecord running = service.submit(submission("req-running-cancel", 1), NOW);
        assertThrows(IllegalStateException.class, () -> service.cancel(running.jobId(), NOW));
        assertEquals("STARTING", jobMapper.findById(running.jobId()).status());
    }

    @Test
    void listsJobsInBoundedPagesWithoutRepeats()
    {
        for (int i = 0; i < 55; i++)
            jobMapper.insert(new JobRecord(null, "uuid-" + i, 10L, null, 3, 0L,
                    "[]", "[]", "{}", "CANCELED", "key-" + i, null, null, NOW, NOW));
        List<JobRecord> first = service.list(10L, false, 50, null);
        List<JobRecord> second = service.list(10L, false, 50, first.get(first.size() - 1).jobId());
        assertEquals(50, first.size());
        assertEquals(5, second.size());
        assertEquals(55L, first.get(0).jobId());
        assertEquals(5L, second.get(0).jobId());
    }

    @Test
    void quarantineKeepsLeasesAndBlocksDispatch()
    {
        JobRecord job = service.submit(submission("req-8", 1), NOW);
        commandMapper.setStatus(job.startCommandId(), "RUNNING");
        service.sync(job.jobId(), NOW);
        JobRecord quarantined = service.quarantine(job.jobId(), NOW);
        assertEquals(JobStatus.QUARANTINED.name(), quarantined.status());
        assertEquals(0, leaseService.releaseCalls);
    }

    @Test
    void rejectsNonPositiveOwner()
    {
        JobSubmission noOwner = new JobSubmission(0L, 1, 8000L, null, null, "req-bad",
                "/data/fixtures", "python3", List.of("train.py"), Map.of(), 300L);
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(noOwner, NOW));
    }

    // ---- Fakes ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T noop(Class<T> type)
    {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] { type }, (proxy, method, args) ->
                {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    private final class InMemoryJobMapper implements com.ruoyi.train.core.job.mapper.JobMapper
    {
        private final Map<Long, JobRecord> store = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public int insert(JobRecord job)
        {
            long id = ids.incrementAndGet();
            JobRecord persisted = new JobRecord(id, job.jobUuid(), job.ownerUserId(),
                    job.runnerId(), job.gpuCount(), job.minMemoryMiB(),
                    job.allowedGpuUuidsJson(), job.deniedGpuUuidsJson(),
                    job.processSpecJson(), job.status(), job.requestKey(),
                    job.startCommandId(), job.stopCommandId(), job.createdAt(),
                    job.updatedAt());
            store.put(id, persisted);
            return 1;
        }

        @Override
        public JobRecord findById(long jobId) { return store.get(jobId); }

        @Override
        public JobRecord findByRequestKey(String requestKey)
        {
            return store.values().stream()
                    .filter(j -> j.requestKey().equals(requestKey)).findFirst().orElse(null);
        }

        @Override
        public JobRecord findByCommandUuid(String commandUuid)
        {
            return store.values().stream().filter(j ->
                    (j.startCommandId() != null && commandMapper.findById(j.startCommandId()).commandUuid().equals(commandUuid))
                    || (j.stopCommandId() != null && commandMapper.findById(j.stopCommandId()).commandUuid().equals(commandUuid)))
                    .findFirst().orElse(null);
        }

        @Override
        public List<JobRecord> listByOwner(Long ownerUserId, boolean isAdmin, int limit, Long beforeJobId)
        {
            return store.values().stream()
                    .filter(j -> isAdmin || j.ownerUserId().equals(ownerUserId))
                    .filter(j -> beforeJobId == null || j.jobId() < beforeJobId)
                    .sorted((a, b) -> Long.compare(b.jobId(), a.jobId()))
                    .limit(limit).toList();
        }

        @Override
        public int countActive(Long ownerUserId, boolean isAdmin)
        {
            return (int) store.values().stream()
                    .filter(j -> isAdmin || j.ownerUserId().equals(ownerUserId))
                    .filter(j -> List.of("ALLOCATING", "ALLOCATED", "STARTING", "RUNNING", "STOPPING").contains(j.status()))
                    .count();
        }

        @Override
        public int compareAndSetStatus(long jobId, String expected, String next, Instant updatedAt)
        {
            JobRecord current = store.get(jobId);
            if (current == null || !current.status().equals(expected)) return 0;
            store.put(jobId, with(current, "status", next, "updated", updatedAt));
            return 1;
        }

        @Override
        public int attachStartCommand(long jobId, long runnerId, long startCommandId,
                String processSpecJson, Instant updatedAt)
        {
            JobRecord current = store.get(jobId);
            if (current == null) return 0;
            JobRecord next = new JobRecord(current.jobId(), current.jobUuid(),
                    current.ownerUserId(), runnerId, current.gpuCount(), current.minMemoryMiB(),
                    current.allowedGpuUuidsJson(), current.deniedGpuUuidsJson(),
                    processSpecJson, JobStatus.STARTING.name(), current.requestKey(),
                    startCommandId, current.stopCommandId(), current.createdAt(), updatedAt);
            store.put(jobId, next);
            return 1;
        }

        @Override
        public int attachStopCommand(long jobId, long stopCommandId, Instant updatedAt)
        {
            JobRecord current = store.get(jobId);
            if (current == null) return 0;
            store.put(jobId, new JobRecord(current.jobId(), current.jobUuid(),
                    current.ownerUserId(), current.runnerId(), current.gpuCount(),
                    current.minMemoryMiB(), current.allowedGpuUuidsJson(),
                    current.deniedGpuUuidsJson(), current.processSpecJson(),
                    current.status(), current.requestKey(), current.startCommandId(),
                    stopCommandId, current.createdAt(), updatedAt));
            return 1;
        }
    }

    private static JobRecord with(JobRecord c, String f1, Object v1, String f2, Object v2)
    {
        return new JobRecord(c.jobId(), c.jobUuid(), c.ownerUserId(), c.runnerId(),
                c.gpuCount(), c.minMemoryMiB(), c.allowedGpuUuidsJson(),
                c.deniedGpuUuidsJson(), c.processSpecJson(), (String) v1, c.requestKey(),
                c.startCommandId(), c.stopCommandId(), c.createdAt(), (Instant) v2);
    }

    private static final class InMemoryCommandMapper implements RunnerCommandMapper
    {
        private final Map<Long, RunnerCommandRecord> store = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();

        RunnerCommandRecord add(String type, String uuid, String status)
        {
            long id = ids.incrementAndGet();
            RunnerCommandRecord record = new RunnerCommandRecord(id, uuid, 1L, type,
                    "{}", "key-" + id, status, null, 0, null, null, NOW, NOW);
            store.put(id, record);
            return record;
        }

        void setStatus(Long id, String status)
        {
            RunnerCommandRecord c = store.get(id);
            store.put(id, new RunnerCommandRecord(c.commandId(), c.commandUuid(), c.runnerId(),
                    c.commandType(), c.payload(), c.idempotencyKey(), status, c.leaseUntil(),
                    c.attemptCount(), c.result(), c.errorCode(), c.createdAt(), c.updatedAt()));
        }

        @Override
        public RunnerCommandRecord findById(Long commandId) { return store.get(commandId); }

        @Override
        public RunnerCommandRecord findByRunnerAndIdempotencyKey(Long runnerId, String key)
        {
            return store.values().stream()
                    .filter(r -> r.runnerId().equals(runnerId) && r.idempotencyKey().equals(key))
                    .findFirst().orElse(null);
        }

        @Override
        public int insert(RunnerCommandRecord command)
        {
            store.put(command.commandId(), command);
            return 1;
        }

        @Override
        public RunnerCommandRecord findByUuid(String commandUuid) { return null; }

        @Override
        public RunnerCommandRecord findByUuidForUpdate(String commandUuid) { return null; }

        @Override
        public Long selectLeaseCandidateForUpdate(Long runnerId, Instant now) { return null; }

        @Override
        public int markLeased(Long runnerId, Long commandId, Instant now, Instant leasedUntil)
        {
            return 0;
        }

        @Override
        public int markRunning(Long runnerId, Long commandId, int attempt, Instant now,
                Instant leaseUntil) { return 0; }

        @Override
        public int complete(Long runnerId, Long commandId, int attempt, String status,
                String result, String errorCode, Instant now) { return 0; }

        @Override
        public int markRejected(Long runnerId, Long commandId, int attempt, Instant now,
                String result, String errorCode, Instant leaseUntil) { return 0; }

        @Override
        public int advanceReconciled(Long runnerId, Long commandId, int attempt,
                String expectedStatus, String nextStatus, Instant now) { return 0; }
    }

    private static final class FakeLeaseService extends GpuLeaseService
    {
        int allocateCalls, renewCalls, releaseCalls;
        long lastOwnerUserId;
        private final List<GpuLeaseRecord> active = new ArrayList<>();

        FakeLeaseService()
        {
            super(noop(com.ruoyi.train.core.gpu.mapper.GpuLeaseMapper.class),
                    new com.ruoyi.train.core.gpu.service.GpuCandidateSelector());
        }

        @Override
        public List<GpuLeaseRecord> allocate(long jobId,
                com.ruoyi.train.core.gpu.service.GpuAllocationRequest request,
                String key, Instant now)
        {
            allocateCalls++;
            lastOwnerUserId = request.ownerUserId();
            active.clear();
            for (int i = 0; i < request.gpuCount(); i++)
                active.add(new GpuLeaseRecord((long) (i + 1), "lease-" + i, jobId, 1L,
                        "gpu-" + i, "ACTIVE", key, now, now.plusSeconds(60), null, null,
                        0, now, now));
            return List.copyOf(active);
        }

        @Override
        public List<GpuLeaseRecord> findActiveByJob(long jobId)
        {
            return List.copyOf(active);
        }

        @Override
        public void renew(long leaseId, long jobId, Instant now) { renewCalls++; }

        @Override
        public void release(long leaseId, long jobId, Instant now)
        {
            releaseCalls++;
            active.clear();
        }
    }

    private static final class FakeCommandService extends RunnerCommandService
    {
        int createCalls, stopCalls;
        ProcessSpec lastProcessSpec;
        private final InMemoryCommandMapper mapper;

        FakeCommandService(InMemoryCommandMapper mapper)
        {
            super(noop(com.ruoyi.train.core.runner.mapper.RunnerCommandMapper.class),
                    new ObjectMapper());
            this.mapper = mapper;
        }

        @Override
        public RunnerCommandRecord createFixtureCommand(String runnerId, ProcessSpec spec,
                long creatorUserId)
        {
            createCalls++;
            lastProcessSpec = spec;
            return mapper.add("START_PROCESS", java.util.UUID.randomUUID().toString(), "QUEUED");
        }

        @Override
        public RunnerCommandRecord requestStop(String runnerId, java.util.UUID commandId)
        {
            RunnerCommandRecord existing = mapper.store.values().stream()
                    .filter(r -> r.commandType().equals("STOP_PROCESS")).findFirst().orElse(null);
            if (existing != null)
                return existing;
            stopCalls++;
            return mapper.add("STOP_PROCESS", java.util.UUID.randomUUID().toString(), "QUEUED");
        }
    }
}
