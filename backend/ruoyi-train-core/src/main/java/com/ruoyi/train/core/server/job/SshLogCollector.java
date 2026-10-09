package com.ruoyi.train.core.server.job;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.observability.AlertEvaluator;
import com.ruoyi.train.core.observability.TrainingMetricParser;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.ssh.SshGateway;

/** Pulls bounded append-only SSH logs; offsets advance only with a committed database chunk. */
@Component
public final class SshLogCollector
{
    private static final Logger log = LoggerFactory.getLogger(SshLogCollector.class);
    private static final int MAX_CHUNK = 65536;
    private final JobMapper jobs;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final SshGateway gateway;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SystemdCommandBuilder commands = new SystemdCommandBuilder();
    private final AlertEvaluator alerts = new AlertEvaluator();

    public SshLogCollector(JobMapper jobs, ServerMapper servers, ServerAccountMapper accounts,
            SshGateway gateway, JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager)
    { this(jobs, servers, accounts, gateway, jdbc, json, manager, Clock.systemUTC()); }

    SshLogCollector(JobMapper jobs, ServerMapper servers, ServerAccountMapper accounts,
            SshGateway gateway, JdbcTemplate jdbc, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock)
    {
        this.jobs = Objects.requireNonNull(jobs);
        this.servers = Objects.requireNonNull(servers);
        this.accounts = Objects.requireNonNull(accounts);
        this.gateway = Objects.requireNonNull(gateway);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 15000)
    public void collect()
    {
        Instant now = clock.instant();
        for (JobRecord job : jobs.listSshJobsForLogs(now.minusSeconds(86400), 50))
        {
            try { collect(job, now); }
            catch (RuntimeException failure) { log.warn("SSH log collection deferred for job {}", job.jobId()); }
        }
    }

    void collect(JobRecord job, Instant now)
    {
        if (job == null || !"SSH".equals(job.executionMode())) return;
        ServerRecord server = servers.findById(job.serverId());
        ServerAccountRecord metadata = accounts.findById(job.accountId());
        if (server == null || metadata == null || !job.serverId().equals(metadata.serverId())
                || !job.ownerUserId().equals(metadata.ownerUserId())) return;
        ServerAccountRecord account = accounts.findCredentialsByAuthorizedAccountId(job.accountId());
        if (account == null) return;
        var identity = commands.identity(job.jobId(), java.util.UUID.fromString(job.jobUuid()),
                job.serverId(), job.accountId(), account.allowedRoot());
        List<LogChunk> chunks = gateway.withVerifiedSession(server, account, session -> List.of(
                read(session, job.jobId(), identity, "STDOUT", "stdout.log"),
                read(session, job.jobId(), identity, "STDERR", "stderr.log")));
        for (LogChunk chunk : chunks) if (chunk.content().length > 0) persist(job.jobId(), chunk, now);
    }

    private LogChunk read(SshGateway.SshSession session, long jobId,
            SystemdCommandBuilder.RemoteJobIdentity identity, String stream, String fileName)
    {
        Long offset = jdbc.queryForObject("SELECT COALESCE(MAX(byte_offset + OCTET_LENGTH(content)),0) "
                + "FROM train_ssh_job_log WHERE job_id=? AND stream=?", Long.class, jobId, stream);
        long current = offset == null ? 0 : offset;
        byte[] bytes = session.readPrivateFile(identity.allowedRoot(), identity.workingDirectory(),
                fileName, current, MAX_CHUNK);
        if (bytes == null || bytes.length > MAX_CHUNK) throw new IllegalStateException("SSH_LOG_CHUNK_INVALID");
        return new LogChunk(stream, current, bytes);
    }

    private void persist(long jobId, LogChunk chunk, Instant now)
    {
        transaction.executeWithoutResult(status -> {
            KeyHolder key = new GeneratedKeyHolder();
            int inserted = jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT IGNORE INTO train_ssh_job_log "
                        + "(job_id,stream,byte_offset,content,created_at) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, jobId);
                statement.setString(2, chunk.stream());
                statement.setLong(3, chunk.offset());
                statement.setBytes(4, chunk.content());
                statement.setTimestamp(5, Timestamp.from(now));
                return statement;
            }, key);
            if (inserted == 0) return;
            long eventId = Objects.requireNonNull(key.getKey()).longValue();
            String content = new String(chunk.content(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("\\R"))
            {
                String alert = alerts.match(line);
                if (alert != null) jdbc.update("INSERT IGNORE INTO train_job_alert "
                        + "(job_id,rule_code,severity,message,created_at) VALUES (?,?,'CRITICAL',?,?)",
                        jobId, alert, alert + " detected in training log", Timestamp.from(now));
                for (TrainingMetricParser.Value metric : TrainingMetricParser.parse(line))
                    jdbc.update("INSERT IGNORE INTO train_ssh_job_metric "
                                    + "(job_id,event_id,metric_name,metric_value,step_number,sampled_at) VALUES (?,?,?,?,?,?)",
                            jobId, eventId, metric.name(), metric.value(), metric.step(), Timestamp.from(now));
            }
        });
    }

    private record LogChunk(String stream, long offset, byte[] content) { }
}
