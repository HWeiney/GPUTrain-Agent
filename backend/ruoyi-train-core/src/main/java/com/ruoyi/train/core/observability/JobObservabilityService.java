package com.ruoyi.train.core.observability;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.runner.protocol.RunnerLogAck;
import com.ruoyi.train.runner.protocol.RunnerLogChunk;
import com.ruoyi.train.runner.protocol.RunnerLogStream;

@Service
public class JobObservabilityService
{
    private final JdbcTemplate jdbc;
    private final JobMapper jobs;
    private final RunnerCommandMapper commands;
    private final AlertEvaluator alerts = new AlertEvaluator();

    public JobObservabilityService(JdbcTemplate jdbc, JobMapper jobs, RunnerCommandMapper commands)
    { this.jdbc = Objects.requireNonNull(jdbc); this.jobs = Objects.requireNonNull(jobs); this.commands = Objects.requireNonNull(commands); }

    @Transactional
    public RunnerLogAck ingest(String runnerId, RunnerLogChunk chunk)
    {
        RunnerCommandRecord command = commands.findByUuid(chunk.commandId().toString());
        if (command == null || !command.runnerId().equals(Long.valueOf(runnerId)))
            throw new IllegalArgumentException("Runner command not found");
        JobRecord job = jobs.findByCommandUuid(chunk.commandId().toString());
        if (job == null || !command.runnerId().equals(job.runnerId()))
            throw new IllegalArgumentException("Job command not found");
        jdbc.update("INSERT IGNORE INTO train_log_cursor(command_id, stream, next_offset) VALUES (?, ?, 0)",
                command.commandId(), chunk.stream().name());
        Long next = jdbc.queryForObject("SELECT next_offset FROM train_log_cursor WHERE command_id=? AND stream=? FOR UPDATE",
                Long.class, command.commandId(), chunk.stream().name());
        if (chunk.offset() < next)
        {
            List<byte[]> prior = jdbc.query("SELECT content FROM train_job_log WHERE command_id=? AND stream=? AND byte_offset=?",
                    (rs, row) -> rs.getBytes(1), command.commandId(), chunk.stream().name(), chunk.offset());
            if (!prior.isEmpty() && java.util.Arrays.equals(prior.get(0), chunk.content())) return new RunnerLogAck(next, false);
            throw new IllegalArgumentException("Conflicting log retry");
        }
        if (chunk.offset() != next) throw new IllegalArgumentException("Log offset gap");
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO train_job_log(job_id, command_id, stream, byte_offset, content, created_at) VALUES (?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, job.jobId()); statement.setLong(2, command.commandId());
            statement.setString(3, chunk.stream().name()); statement.setLong(4, chunk.offset());
            statement.setBytes(5, chunk.content()); statement.setTimestamp(6, Timestamp.from(Instant.now()));
            return statement;
        }, key);
        jdbc.update("UPDATE train_log_cursor SET next_offset=? WHERE command_id=? AND stream=?",
                chunk.nextOffset(), command.commandId(), chunk.stream().name());
        long eventId = Objects.requireNonNull(key.getKey()).longValue();
        analyze(job.jobId(), eventId, chunk.content());
        return new RunnerLogAck(chunk.nextOffset(), true);
    }

    public long nextOffset(String runnerId, String commandUuid, RunnerLogStream stream)
    {
        RunnerCommandRecord command = commands.findByUuid(commandUuid);
        if (command == null || !command.runnerId().equals(Long.valueOf(runnerId)))
            throw new IllegalArgumentException("Runner command not found");
        if (jobs.findByCommandUuid(commandUuid) == null) return -1;
        Long value = jdbc.queryForObject("SELECT COALESCE((SELECT next_offset FROM train_log_cursor WHERE command_id=? AND stream=?), 0)",
                Long.class, command.commandId(), stream.name());
        return value == null ? 0 : value;
    }

    public List<JobLogEvent> logs(long jobId, long afterEventId, int limit)
    {
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT log_id, stream, byte_offset, content, created_at FROM train_job_log WHERE job_id=? AND log_id>? ORDER BY log_id LIMIT ?",
                (rs, row) -> new JobLogEvent(rs.getLong("log_id"), rs.getString("stream"), rs.getLong("byte_offset"),
                        rs.getLong("byte_offset") + rs.getBytes("content").length,
                        new String(rs.getBytes("content"), StandardCharsets.UTF_8), rs.getTimestamp("created_at").toInstant()),
                jobId, afterEventId, bounded);
    }

    public List<JobMetric> metrics(long jobId)
    {
        return jdbc.query("SELECT metric_id, metric_name, metric_value, step_number, sampled_at FROM train_job_metric WHERE job_id=? ORDER BY metric_id DESC LIMIT 500",
                (rs, row) -> new JobMetric(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        (Long) rs.getObject(4), rs.getTimestamp(5).toInstant()), jobId);
    }

    public List<JobAlert> alerts(long jobId)
    {
        return jdbc.query("SELECT alert_id, rule_code, severity, message, created_at FROM train_job_alert WHERE job_id=? ORDER BY alert_id DESC LIMIT 100",
                (rs, row) -> new JobAlert(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), jobId);
    }

    public List<GpuResourceMetric> gpuResources(long jobId)
    {
        return jdbc.query("SELECT DISTINCT s.gpu_uuid, s.utilization_percent, s.total_memory_mib, s.used_memory_mib, s.sampled_at "
                + "FROM train_gpu_snapshot s JOIN train_gpu_lease l ON l.gpu_uuid=s.gpu_uuid "
                + "WHERE l.job_id=? AND s.sampled_at BETWEEN l.acquired_at AND COALESCE(l.released_at, CURRENT_TIMESTAMP(3)) "
                + "ORDER BY s.sampled_at DESC LIMIT 1000",
                (rs, row) -> new GpuResourceMetric(rs.getString(1), rs.getInt(2), rs.getLong(3),
                        rs.getLong(4), rs.getTimestamp(5).toInstant()), jobId);
    }

    private void analyze(long jobId, long eventId, byte[] bytes)
    {
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\R"))
        {
            String rule = alerts.match(line);
            if (rule != null) jdbc.update("INSERT IGNORE INTO train_job_alert(job_id, rule_code, severity, message, created_at) VALUES (?, ?, 'CRITICAL', ?, ?)",
                    jobId, rule, rule + " detected in training log", Timestamp.from(Instant.now()));
            for (TrainingMetricParser.Value metric : TrainingMetricParser.parse(line))
                jdbc.update("INSERT IGNORE INTO train_job_metric(job_id, log_id, metric_name, metric_value, step_number, sampled_at) VALUES (?, ?, ?, ?, ?, ?)",
                        jobId, eventId, metric.name(), metric.value(), metric.step(), Timestamp.from(Instant.now()));
        }
    }
}
