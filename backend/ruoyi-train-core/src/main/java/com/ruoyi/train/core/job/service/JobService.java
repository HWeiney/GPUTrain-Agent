package com.ruoyi.train.core.job.service;

import java.time.Instant;
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
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.domain.JobStateMachine;
import com.ruoyi.train.core.job.domain.JobStatus;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.server.job.SshJobSubmissionValidator;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService;
import com.ruoyi.train.core.server.service.ServerInventoryService;
import com.ruoyi.train.core.server.gpu.SshGpuLeaseService;

/** SSH job lifecycle with read-only access to historical jobs. */
@Service
public class JobService
{
    private final JobMapper jobMapper;
    private final ObjectMapper json;
    private final ServerInventoryService serverInventory;
    private final ServerDeviceQueryService serverDevices;
    private final SshJobSubmissionValidator sshSubmissionValidator;
    private final SshGpuLeaseService sshLeases;

    @Autowired
    public JobService(JobMapper jobMapper, ObjectMapper json, ServerInventoryService serverInventory,
            ServerDeviceQueryService serverDevices, SshGpuLeaseService sshLeases)
    {
        this.jobMapper = Objects.requireNonNull(jobMapper);
        this.json = Objects.requireNonNull(json);
        this.serverInventory = serverInventory;
        this.serverDevices = serverDevices;
        this.sshSubmissionValidator = new SshJobSubmissionValidator();
        this.sshLeases = sshLeases;
    }

    @Transactional
    public JobRecord submit(JobSubmission submission, Instant now)
    {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(now, "now");
        if (submission.ownerUserId() <= 0)
            throw new IllegalArgumentException("Owner user id must be positive");
        if (submission.serverId() == null || submission.accountId() == null)
            throw new IllegalArgumentException("SSH server and account are required");
        if (submission.environment().keySet().stream().anyMatch("CUDA_VISIBLE_DEVICES"::equalsIgnoreCase))
            throw new IllegalArgumentException("CUDA_VISIBLE_DEVICES is assigned by the scheduler");
        JobRecord existing = jobMapper.findByRequestKey(submission.requestKey());
        if (existing != null)
        {
            if (!"SSH".equals(existing.executionMode())
                    || !Objects.equals(existing.ownerUserId(), submission.ownerUserId())
                    || !Objects.equals(existing.serverId(), submission.serverId())
                    || !Objects.equals(existing.accountId(), submission.accountId()))
                throw new IllegalArgumentException("REQUEST_KEY_CONFLICT");
            return existing;
        }
        return queueSsh(submission, now);
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
                submission.minFreeMemoryMiB(), writeJson(prepared.gpuUuids()), writeJson(submission.deniedUuids()),
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
            return device != null && device.schedulable()
                    && device.totalMemoryMiB() - device.usedMemoryMiB() >= job.minMemoryMiB();
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
    public JobRecord cancel(long jobId, Instant now)
    {
        JobRecord job = requireJob(jobId);
        if (!"SSH".equals(job.executionMode())) throw new IllegalArgumentException("LEGACY_JOB_READ_ONLY");
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

    private JobRecord requireJob(long jobId)
    {
        JobRecord job = jobMapper.findById(jobId);
        if (job == null) throw new IllegalArgumentException("Job not found");
        return job;
    }

    private String writeJson(Object value)
    {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure)
        { throw new IllegalArgumentException("Unable to serialize job field", failure); }
    }
}
