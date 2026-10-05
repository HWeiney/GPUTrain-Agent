package com.ruoyi.train.core.job.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
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

    public JobService(JobMapper jobMapper, GpuLeaseService leaseService,
            RunnerCommandService commandService, RunnerCommandMapper commandMapper,
            ObjectMapper json)
    {
        this.jobMapper = Objects.requireNonNull(jobMapper);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.commandService = Objects.requireNonNull(commandService);
        this.commandMapper = Objects.requireNonNull(commandMapper);
        this.json = Objects.requireNonNull(json);
    }

    @Transactional
    public JobRecord submit(JobSubmission submission, Instant now)
    {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(now, "now");
        if (submission.ownerUserId() <= 0)
            throw new IllegalArgumentException("Owner user id must be positive");
        JobRecord existing = jobMapper.findByRequestKey(submission.requestKey());
        if (existing != null)
            return existing;

        JobRecord job = new JobRecord(null, UUID.randomUUID().toString(),
                submission.ownerUserId(), null, submission.gpuCount(),
                submission.minMemoryMiB(), writeJson(submission.allowedUuids()),
                writeJson(submission.deniedUuids()), writeJson(submission.toProcessSpec()),
                JobStatus.QUEUED.name(), submission.requestKey(), null, null, now, now);
        if (jobMapper.insert(job) != 1)
            throw new IllegalStateException("Unable to create job");
        JobRecord persisted = jobMapper.findByRequestKey(submission.requestKey());
        long jobId = persisted.jobId();

        GpuAllocationRequest allocation = new GpuAllocationRequest(submission.gpuCount(),
                submission.minMemoryMiB(), submission.allowedUuids(),
                submission.deniedUuids(), true);
        List<GpuLeaseRecord> leases = leaseService.allocate(jobId, allocation,
                "lease:" + submission.requestKey(), now);
        long runnerId = leases.get(0).runnerId();

        ProcessSpec spec = submission.toProcessSpec();
        RunnerCommandRecord start = commandService.createFixtureCommand(
                String.valueOf(runnerId), spec, submission.ownerUserId());

        if (jobMapper.attachStartCommand(jobId, runnerId, start.commandId(), now) != 1)
            throw new IllegalStateException("Unable to attach start command");
        return jobMapper.findById(jobId);
    }

    @Transactional
    public JobRecord sync(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (job.startCommandId() == null || JobStatus.valueOf(job.status()).isTerminal())
            return job;
        RunnerCommandRecord command = commandMapper.findById(job.startCommandId());
        switch (command.status())
        {
            case "RUNNING" -> advance(job, JobStatus.RUNNING, now, true);
            case "SUCCEEDED" -> finish(job, JobStatus.SUCCEEDED, now);
            case "FAILED" -> finish(job, JobStatus.FAILED, now);
            case "STOPPED" -> finish(job, JobStatus.STOPPED, now);
            case "REJECTED" -> finish(job, JobStatus.FAILED, now);
            default -> { /* QUEUED / LEASED remain STARTING */ }
        }
        return jobMapper.findById(jobId);
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

    /**
     * Put a job into the safe QUARANTINED state because the control plane cannot prove
     * its device is safe. Active leases are deliberately not released, so the GPU cannot
     * be handed to another job until the situation is re-probed.
     */
    public List<JobRecord> list(long userId, boolean isAdmin)
    {
        return jobMapper.listByOwner(userId, isAdmin);
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
