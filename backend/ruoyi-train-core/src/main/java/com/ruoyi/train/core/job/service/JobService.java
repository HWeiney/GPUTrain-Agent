package com.ruoyi.train.core.job.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.gpu.domain.GpuLeaseRecord;
import com.ruoyi.train.core.gpu.service.GpuAllocationRequest;
import com.ruoyi.train.core.gpu.service.GpuLeaseService;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.domain.JobStateMachine;
import com.ruoyi.train.core.job.domain.JobStatus;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.runner.protocol.ProcessSpec;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.core.server.job.SshJobSubmissionValidator;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService;
import com.ruoyi.train.core.server.service.ServerInventoryService;
import com.ruoyi.train.core.server.gpu.SshGpuLeaseService;

/**
 * Orchestrates the training job lifecycle: submission, atomic GPU allocation, START/STOP
 * command creation and status synchronization. Jobs never receive Runner process events
 * directly; they are driven through the phase-two command pipeline, which keeps the
 * job, command, process and lease relationships stable.
 */
@Service
public class JobService
{
    private final JobMapper jobMapper;
    private final GpuLeaseService leaseService;
    private final RunnerCommandService commandService;
    private final RunnerCommandMapper commandMapper;
    private final ObjectMapper json;
    private final ServerInventoryService serverInventory;
    private final ServerDeviceQueryService serverDevices;
    private final SshJobSubmissionValidator sshSubmissionValidator;
    private final SshGpuLeaseService sshLeases;

    @Autowired
    public JobService(JobMapper jobMapper, GpuLeaseService leaseService,
            RunnerCommandService commandService, RunnerCommandMapper commandMapper,
            ObjectMapper json, ServerInventoryService serverInventory,
            ServerDeviceQueryService serverDevices, SshGpuLeaseService sshLeases)
    {
        this.jobMapper = Objects.requireNonNull(jobMapper);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.commandService = Objects.requireNonNull(commandService);
        this.commandMapper = Objects.requireNonNull(commandMapper);
        this.json = Objects.requireNonNull(json);
        this.serverInventory = serverInventory;
        this.serverDevices = serverDevices;
        this.sshSubmissionValidator = new SshJobSubmissionValidator();
        this.sshLeases = sshLeases;
    }

    public JobService(JobMapper jobMapper, GpuLeaseService leaseService,
            RunnerCommandService commandService, RunnerCommandMapper commandMapper, ObjectMapper json)
    {
        this(jobMapper, leaseService, commandService, commandMapper, json, null, null, null);
    }

    @Transactional
    public JobRecord submit(JobSubmission submission, Instant now)
    {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(now, "now");
        if (submission.ownerUserId() <= 0)
            throw new IllegalArgumentException("Owner user id must be positive");
        if (submission.environment().keySet().stream().anyMatch("CUDA_VISIBLE_DEVICES"::equalsIgnoreCase))
            throw new IllegalArgumentException("CUDA_VISIBLE_DEVICES is assigned by the scheduler");
        JobRecord existing = jobMapper.findByRequestKey(submission.requestKey());
        if (existing != null)
            return existing;
        if (submission.serverId() != null)
            return queueSsh(submission, now);

        JobRecord job = new JobRecord(null, UUID.randomUUID().toString(),
                submission.ownerUserId(), null, submission.gpuCount(),
                submission.minMemoryMiB(), writeJson(submission.allowedUuids()),
                writeJson(submission.deniedUuids()), writeJson(submission.toProcessSpec()),
                JobStatus.QUEUED.name(), submission.requestKey(), null, null, now, now);
        if (jobMapper.insert(job) != 1)
            throw new IllegalStateException("Unable to create job");
        JobRecord persisted = jobMapper.findByRequestKey(submission.requestKey());
        long jobId = persisted.jobId();

        GpuAllocationRequest allocation = new GpuAllocationRequest(submission.ownerUserId(), submission.gpuCount(),
                submission.minMemoryMiB(), submission.allowedUuids(),
                submission.deniedUuids(), true, submission.minFreeMemoryMiB(),
                submission.allowExternalOccupancy());
        List<GpuLeaseRecord> leases = leaseService.allocate(jobId, allocation,
                "lease:" + submission.requestKey(), now);
        long runnerId = leases.get(0).runnerId();

        ProcessSpec requested = submission.toProcessSpec();
        Map<String, String> assignedEnvironment = new HashMap<>(requested.environment());
        assignedEnvironment.put("CUDA_VISIBLE_DEVICES", leases.stream()
                .map(GpuLeaseRecord::gpuUuid).collect(Collectors.joining(",")));
        ProcessSpec spec = new ProcessSpec(requested.workingDirectory(), requested.executable(),
                requested.arguments(), assignedEnvironment, requested.timeoutSeconds(),
                requested.idempotencyKey());
        RunnerCommandRecord start = commandService.createFixtureCommand(
                String.valueOf(runnerId), spec, submission.ownerUserId());

        if (jobMapper.attachStartCommand(jobId, runnerId, start.commandId(), writeJson(spec), now) != 1)
            throw new IllegalStateException("Unable to attach start command");
        return jobMapper.findById(jobId);
    }

    private JobRecord queueSsh(JobSubmission submission, Instant now)
    {
        if (serverInventory == null || serverDevices == null)
            throw new IllegalStateException("SSH job scheduling is not enabled yet");
        var detail = serverInventory.detail(submission.serverId(), submission.accountId(), submission.ownerUserId());
        var observed = serverDevices.list(submission.serverId(), submission.accountId(), submission.ownerUserId());
        String uuid = UUID.randomUUID().toString();
        var prepared = sshSubmissionValidator.validate(submission, detail, observed, now, UUID.fromString(uuid));
        JobRecord queued = new JobRecord(null, uuid, submission.ownerUserId(), null, 3,
                submission.minMemoryMiB(), writeJson(prepared.gpuUuids()), writeJson(submission.deniedUuids()),
                writeJson(submission.toProcessSpec()), JobStatus.QUEUED.name(), submission.requestKey(),
                null, null, now, now, "SSH", submission.serverId(), submission.accountId(),
                prepared.unitName(), submission.workingDirectory());
        if (jobMapper.insert(queued) != 1) throw new IllegalStateException("Unable to queue SSH job");
        return jobMapper.findByRequestKey(submission.requestKey());
    }

    @Transactional
    public boolean allocateSshJob(long jobId, Instant now)
    {
        if (sshLeases == null || serverDevices == null) throw new IllegalStateException("SSH scheduler is not enabled");
        JobRecord job = requireJob(jobId);
        if (!"SSH".equals(job.executionMode()) || !JobStatus.QUEUED.name().equals(job.status())) return false;
        if (jobMapper.compareAndSetStatus(jobId, JobStatus.QUEUED.name(), JobStatus.ALLOCATING.name(), now) != 1)
            return false;
        List<String> selected;
        try { selected = json.readValue(job.allowedGpuUuidsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
        catch (Exception invalid) { throw new IllegalStateException("GPU_SELECTION_CORRUPT"); }
        var devices = serverDevices.list(job.serverId(), job.accountId(), job.ownerUserId());
        Map<String, ServerDeviceQueryService.DeviceView> byUuid = devices.stream()
                .collect(Collectors.toMap(ServerDeviceQueryService.DeviceView::gpuUuid, device -> device, (a, b) -> a));
        boolean available = selected.size() == 3 && selected.stream().allMatch(uuid -> {
            var device = byUuid.get(uuid);
            return device != null && device.schedulable();
        });
        if (!available)
        {
            if (jobMapper.compareAndSetStatus(jobId, JobStatus.ALLOCATING.name(), JobStatus.QUEUED.name(), now) != 1)
                throw new IllegalStateException("Job status changed during allocation");
            return false;
        }
        if (!sshLeases.tryAcquire(jobId, job.jobUuid(), job.serverId(), job.accountId(), selected, now))
        {
            if (jobMapper.compareAndSetStatus(jobId, JobStatus.ALLOCATING.name(), JobStatus.QUEUED.name(), now) != 1)
                throw new IllegalStateException("Job status changed during allocation");
            return false;
        }
        if (jobMapper.compareAndSetStatus(jobId, JobStatus.ALLOCATING.name(), JobStatus.ALLOCATED.name(), now) != 1)
            throw new IllegalStateException("Unable to finalize SSH GPU allocation");
        return true;
    }

    @Transactional
    public JobRecord claimSshStart(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (!"SSH".equals(job.executionMode()) || !JobStatus.ALLOCATED.name().equals(job.status())) return null;
        if (jobMapper.compareAndSetStatus(jobId, JobStatus.ALLOCATED.name(), JobStatus.STARTING.name(), now) != 1)
            return null;
        return jobMapper.findById(jobId);
    }

    @Transactional
    public JobRecord updateSshStatus(long jobId, String expectedStatus, JobStatus next, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (!"SSH".equals(job.executionMode())) throw new IllegalArgumentException("SSH_JOB_REQUIRED");
        JobStatus current = JobStatus.valueOf(job.status());
        if (!current.name().equals(expectedStatus)) return job;
        if (current == next) return job;
        JobStateMachine.requireTransition(current, next);
        if (jobMapper.compareAndSetStatus(jobId, current.name(), next.name(), now) != 1)
            return jobMapper.findById(jobId);
        if (next.isTerminal() && sshLeases != null) sshLeases.releaseForJob(jobId, now);
        return jobMapper.findById(jobId);
    }

    @Transactional
    public JobRecord sync(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (job.startCommandId() == null || JobStatus.valueOf(job.status()).isTerminal())
            return job;
        if (JobStatus.QUARANTINED.name().equals(job.status())) return job;
        if (job.stopCommandId() != null)
        {
            RunnerCommandRecord stop = commandMapper.findById(job.stopCommandId());
            if (stop != null && "STOPPED".equals(stop.status()))
            {
                finish(job, JobStatus.STOPPED, now);
                return jobMapper.findById(jobId);
            }
            if (stop != null && ("FAILED".equals(stop.status()) || "REJECTED".equals(stop.status())))
            {
                transition(job, JobStatus.QUARANTINED, now);
                return jobMapper.findById(jobId);
            }
        }
        RunnerCommandRecord command = commandMapper.findById(job.startCommandId());
        switch (command.status())
        {
            case "RUNNING" -> {
                if (!JobStatus.RUNNING.name().equals(job.status())
                        && !JobStatus.STOPPING.name().equals(job.status()))
                    advance(job, JobStatus.RUNNING, now, true);
            }
            case "SUCCEEDED" -> finish(job, JobStatus.SUCCEEDED, now);
            case "FAILED" -> finish(job, JobStatus.FAILED, now);
            case "STOPPED" -> finish(job, JobStatus.STOPPED, now);
            case "REJECTED" -> finish(job, JobStatus.FAILED, now);
            default -> { /* QUEUED / LEASED remain STARTING */ }
        }
        return jobMapper.findById(jobId);
    }

    /** Synchronize an associated job immediately after a Runner command report. */
    @Transactional
    public void syncCommand(UUID commandUuid, Instant now)
    {
        JobRecord job = jobMapper.findByCommandUuid(commandUuid.toString());
        if (job != null) sync(job.jobId(), now);
    }

    @Transactional
    public JobRecord stop(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (job.startCommandId() == null)
            throw new IllegalStateException("Job has no start command");
        JobStatus status = JobStatus.valueOf(job.status());
        if (status.isTerminal())
            return job;
        RunnerCommandRecord start = commandMapper.findById(job.startCommandId());
        RunnerCommandRecord stop = commandService.requestStop(
                String.valueOf(start.runnerId()), UUID.fromString(start.commandUuid()));
        if (jobMapper.attachStopCommand(jobId, stop.commandId(), now) != 1)
            throw new IllegalStateException("Unable to attach stop command");
        if (status == JobStatus.RUNNING)
            transition(job, JobStatus.STOPPING, now);
        return jobMapper.findById(jobId);
    }

    @Transactional
    public JobRecord cancel(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        JobStatus status = JobStatus.valueOf(job.status());
        if (status == JobStatus.CANCELED) return job;
        boolean sshAllocated = "SSH".equals(job.executionMode()) && status == JobStatus.ALLOCATED;
        if ((status != JobStatus.PENDING && status != JobStatus.QUEUED && !sshAllocated)
                || job.startCommandId() != null)
            throw new IllegalStateException("Job can no longer be canceled; refresh its status");
        JobStateMachine.requireTransition(status, JobStatus.CANCELED);
        if (jobMapper.compareAndSetStatus(jobId, status.name(), JobStatus.CANCELED.name(), now) != 1)
            throw new IllegalStateException("Job status changed; refresh and retry");
        if (sshAllocated && sshLeases != null) sshLeases.releaseForJob(jobId, now);
        return jobMapper.findById(jobId);
    }

    @Transactional
    public JobRecord beginSshStop(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (!"SSH".equals(job.executionMode())) throw new IllegalArgumentException("SSH_JOB_REQUIRED");
        JobStatus status = JobStatus.valueOf(job.status());
        if (status.isTerminal()) return job;
        if (status == JobStatus.QUEUED || status == JobStatus.ALLOCATED) return cancel(jobId, now);
        if (status == JobStatus.STOPPING) return job;
        JobStateMachine.requireTransition(status, JobStatus.STOPPING);
        if (jobMapper.compareAndSetStatus(jobId, status.name(), JobStatus.STOPPING.name(), now) != 1)
            return jobMapper.findById(jobId);
        return jobMapper.findById(jobId);
    }

    /**
     * Put a job into the safe QUARANTINED state because the control plane cannot prove
     * its device is safe. Active leases are deliberately not released, so the GPU cannot
     * be handed to another job until the situation is re-probed.
     */
    public List<JobRecord> list(long userId, boolean isAdmin)
    {
        return list(userId, isAdmin, 50, null);
    }

    public List<JobRecord> list(long userId, boolean isAdmin, int limit, Long beforeJobId)
    {
        if (limit < 1 || limit > 100 || (beforeJobId != null && beforeJobId <= 0))
            throw new IllegalArgumentException("Invalid job list cursor or limit");
        return jobMapper.listByOwner(userId, isAdmin, limit, beforeJobId);
    }

    /**
     * Load a job and enforce ownership. A non-admin user cannot access another user's
     * job, so the control plane never leaks another user's task or lets it be stopped.
     */
    public JobRecord getOwned(long jobId, long userId, boolean isAdmin)
    {
        JobRecord job = requireJob(jobId);
        if (!isAdmin && job.ownerUserId() != userId)
            throw new IllegalArgumentException("Job not found");
        return job;
    }

    @Transactional
    public JobRecord quarantine(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (!JobStatus.valueOf(job.status()).isTerminal())
            transition(job, JobStatus.QUARANTINED, now);
        return jobMapper.findById(jobId);
    }

    private void advance(JobRecord job, JobStatus target, Instant now, boolean renewLeases)
    {
        transition(job, target, now);
        if (renewLeases)
        {
            for (GpuLeaseRecord lease : leaseService.findActiveByJob(job.jobId()))
                leaseService.renew(lease.leaseId(), job.jobId(), now);
        }
    }

    private void finish(JobRecord job, JobStatus terminal, Instant now)
    {
        transition(job, terminal, now);
        for (GpuLeaseRecord lease : leaseService.findActiveByJob(job.jobId()))
            leaseService.release(lease.leaseId(), job.jobId(), now);
    }

    private void transition(JobRecord job, JobStatus target, Instant now)
    {
        JobStatus from = JobStatus.valueOf(job.status());
        JobStateMachine.requireTransition(from, target);
        if (jobMapper.compareAndSetStatus(job.jobId(), from.name(), target.name(), now) != 1)
            throw new RunnerProtocolException(com.ruoyi.train.runner.protocol.RunnerErrorCode.COMMAND_STATUS_CONFLICT,
                    "Job status changed concurrently");
    }

    private JobRecord requireJob(long jobId)
    {
        JobRecord job = jobMapper.findById(jobId);
        if (job == null)
            throw new IllegalArgumentException("Job not found");
        return job;
    }

    private String writeJson(Object value)
    {
        try
        {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException failure)
        {
            throw new IllegalArgumentException("Unable to serialize job field", failure);
        }
    }
}
