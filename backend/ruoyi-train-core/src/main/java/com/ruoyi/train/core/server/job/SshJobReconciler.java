package com.ruoyi.train.core.server.job;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.domain.JobStatus;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;

/** Reconciles the database with deterministic remote systemd unit names after transient SSH failures. */
@Component
public final class SshJobReconciler
{
    private static final Logger log = LoggerFactory.getLogger(SshJobReconciler.class);
    private final JobMapper jobs;
    private final JobService jobService;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final SshRemoteJobClient remote;
    private final Clock clock;
    private final SystemdCommandBuilder commands = new SystemdCommandBuilder();

    public SshJobReconciler(JobMapper jobs, JobService jobService, ServerMapper servers,
            ServerAccountMapper accounts, SshRemoteJobClient remote)
    { this(jobs, jobService, servers, accounts, remote, Clock.systemUTC()); }

    SshJobReconciler(JobMapper jobs, JobService jobService, ServerMapper servers,
            ServerAccountMapper accounts, SshRemoteJobClient remote, Clock clock)
    {
        this.jobs = Objects.requireNonNull(jobs);
        this.jobService = Objects.requireNonNull(jobService);
        this.servers = Objects.requireNonNull(servers);
        this.accounts = Objects.requireNonNull(accounts);
        this.remote = Objects.requireNonNull(remote);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void reconcile()
    {
        for (JobRecord job : jobs.listSshForReconciliation(50)) reconcile(job);
    }

    void reconcile(JobRecord job)
    {
        if (job == null || !"SSH".equals(job.executionMode())) return;
        JobStatus current = JobStatus.valueOf(job.status());
        try
        {
            ServerRecord server = servers.findById(job.serverId());
            ServerAccountRecord metadata = accounts.findById(job.accountId());
            if (server == null || metadata == null || !job.serverId().equals(metadata.serverId())
                    || !job.ownerUserId().equals(metadata.ownerUserId()))
                throw new IllegalStateException("SERVER_ACCOUNT_UNAVAILABLE");
            ServerAccountRecord account = accounts.findCredentialsByAuthorizedAccountId(job.accountId());
            if (account == null) throw new IllegalStateException("SERVER_ACCOUNT_UNAVAILABLE");
            var identity = commands.identity(job.jobId(), java.util.UUID.fromString(job.jobUuid()),
                    job.serverId(), job.accountId(), account.allowedRoot());
            SystemdUnitState state = remote.inspect(server, account, identity);
            JobStatus observed = SshJobDispatcher.status(state);
            if (current == JobStatus.STOPPING && state != null && state.loaded() && !state.active())
                observed = JobStatus.STOPPED;
            if (current == JobStatus.UNKNOWN || current == JobStatus.QUARANTINED)
            {
                if (observed == JobStatus.UNKNOWN) return;
                jobService.updateSshStatus(job.jobId(), current.name(), JobStatus.RECONCILING, clock.instant());
                current = JobStatus.RECONCILING;
            }
            if (current != observed)
                jobService.updateSshStatus(job.jobId(), current.name(), observed, clock.instant());
        }
        catch (Exception failure)
        {
            JobRecord latest = jobs.findById(job.jobId());
            if (latest != null && !JobStatus.valueOf(latest.status()).isTerminal()
                    && JobStatus.valueOf(latest.status()) != JobStatus.QUARANTINED
                    && JobStatus.valueOf(latest.status()) != JobStatus.UNKNOWN)
                jobService.updateSshStatus(job.jobId(), latest.status(), JobStatus.UNKNOWN, clock.instant());
            log.warn("SSH reconciliation deferred for job {}", job.jobId());
        }
    }
}
