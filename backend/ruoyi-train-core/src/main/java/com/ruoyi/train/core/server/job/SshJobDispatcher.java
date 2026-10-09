package com.ruoyi.train.core.server.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.domain.JobStatus;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.runner.protocol.ProcessSpec;

/** Claims queued work in short DB transactions and performs SSH outside those transactions. */
@Component
public final class SshJobDispatcher
{
    private static final Logger log = LoggerFactory.getLogger(SshJobDispatcher.class);
    private final JobMapper jobs;
    private final JobService jobService;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final SshRemoteJobClient remote;
    private final SystemdCommandBuilder commands;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public SshJobDispatcher(JobMapper jobs, JobService jobService, ServerMapper servers,
            ServerAccountMapper accounts, SshRemoteJobClient remote, ObjectMapper json)
    {
        this(jobs, jobService, servers, accounts, remote, json, Clock.systemUTC());
    }

    SshJobDispatcher(JobMapper jobs, JobService jobService, ServerMapper servers,
            ServerAccountMapper accounts, SshRemoteJobClient remote, ObjectMapper json, Clock clock)
    {
        this.jobs = Objects.requireNonNull(jobs);
        this.jobService = Objects.requireNonNull(jobService);
        this.servers = Objects.requireNonNull(servers);
        this.accounts = Objects.requireNonNull(accounts);
        this.remote = Objects.requireNonNull(remote);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.commands = new SystemdCommandBuilder();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void dispatch()
    {
        Instant now = clock.instant();
        for (JobRecord queued : jobs.listDueSshJobs(now.minusSeconds(30), 20))
        {
            try { jobService.allocateSshJob(queued.jobId(), now); }
            catch (RuntimeException failure) { log.warn("SSH GPU allocation deferred for job {}", queued.jobId()); }
        }
        for (JobRecord allocated : jobs.listSshToStart(20)) start(allocated.jobId(), clock.instant());
    }

    private void start(long jobId, Instant now)
    {
        JobRecord job = jobService.claimSshStart(jobId, now);
        if (job == null) return;
        try
        {
            ServerRecord server = servers.findById(job.serverId());
            if (server == null) throw new IllegalStateException("SERVER_UNAVAILABLE");
            ServerAccountRecord account = authorizedCredentials(job);
            ProcessSpec spec = json.readValue(job.processSpecJson(), ProcessSpec.class);
            List<String> gpus = json.readValue(job.allowedGpuUuidsJson(), new TypeReference<>() { });
            var identity = commands.identity(job.jobId(), java.util.UUID.fromString(job.jobUuid()),
                    job.serverId(), job.accountId(), account.allowedRoot());
            var profile = new SystemdCommandBuilder.FixedTrainingProfile(spec.workingDirectory(),
                    spec.executable(), spec.arguments(), spec.environment(), spec.timeoutSeconds());
            SystemdUnitState state = remote.start(server, account, identity, profile, gpus);
            jobService.updateSshStatus(jobId, JobStatus.STARTING.name(), status(state), clock.instant());
        }
        catch (Exception failure)
        {
            jobService.updateSshStatus(jobId, JobStatus.STARTING.name(), JobStatus.UNKNOWN, clock.instant());
            log.warn("SSH start outcome is unknown for job {}", jobId);
        }
    }

    public JobRecord stop(long jobId, Instant now)
    {
        JobRecord job = jobService.beginSshStop(jobId, now);
        if (!"SSH".equals(job.executionMode()) || !JobStatus.STOPPING.name().equals(job.status())) return job;
        try
        {
            ServerAccountRecord account = authorizedCredentials(job);
            ServerRecord server = servers.findById(job.serverId());
            if (server == null) throw new IllegalStateException("SERVER_UNAVAILABLE");
            var identity = commands.identity(job.jobId(), java.util.UUID.fromString(job.jobUuid()),
                    job.serverId(), job.accountId(), account.allowedRoot());
            SystemdUnitState state = remote.stop(server, account, identity);
            JobStatus result = state.loaded() && !state.active() ? JobStatus.STOPPED
                    : state.loaded() ? JobStatus.STOPPING : JobStatus.UNKNOWN;
            return jobService.updateSshStatus(jobId, JobStatus.STOPPING.name(), result, clock.instant());
        }
        catch (Exception failure)
        {
            return jobService.updateSshStatus(jobId, JobStatus.STOPPING.name(), JobStatus.UNKNOWN, clock.instant());
        }
    }

    private ServerAccountRecord authorizedCredentials(JobRecord job)
    {
        ServerAccountRecord metadata = accounts.findById(job.accountId());
        if (metadata == null || !job.serverId().equals(metadata.serverId())
                || !job.ownerUserId().equals(metadata.ownerUserId()))
            throw new IllegalStateException("SERVER_ACCOUNT_UNAVAILABLE");
        ServerAccountRecord account = accounts.findCredentialsByAuthorizedAccountId(job.accountId());
        if (account == null) throw new IllegalStateException("SERVER_ACCOUNT_UNAVAILABLE");
        return account;
    }

    static JobStatus status(SystemdUnitState state)
    {
        if (state == null || !state.loaded()) return JobStatus.UNKNOWN;
        if (state.active()) return JobStatus.RUNNING;
        if ("failed".equals(state.activeState()) || !"success".equals(state.result())) return JobStatus.FAILED;
        if ("inactive".equals(state.activeState()) && Integer.valueOf(0).equals(state.mainStatus()))
            return JobStatus.SUCCEEDED;
        return JobStatus.UNKNOWN;
    }
}
