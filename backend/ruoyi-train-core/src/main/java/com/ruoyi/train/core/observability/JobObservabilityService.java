package com.ruoyi.train.core.observability;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;

/** Current SSH observations and read-only legacy history. */
@Service
public class JobObservabilityService
{
    private final JdbcTemplate jdbc;
    private final JobMapper jobs;

    public JobObservabilityService(JdbcTemplate jdbc, JobMapper jobs)
    { this.jdbc = Objects.requireNonNull(jdbc); this.jobs = Objects.requireNonNull(jobs); }

    public List<JobLogEvent> logs(long jobId, long afterEventId, int limit)
    {
        JobRecord job = jobs.findById(jobId);
        if (job != null && "SSH".equals(job.executionMode()))
        {
            int bounded = Math.max(1, Math.min(limit, 500));
            return jdbc.query("SELECT event_id,stream,byte_offset,content,created_at FROM train_ssh_job_log "
                            + "WHERE job_id=? AND event_id>? ORDER BY event_id LIMIT ?",
                    (rs, row) -> new JobLogEvent(rs.getLong("event_id"), rs.getString("stream"),
                            rs.getLong("byte_offset"), rs.getLong("byte_offset") + rs.getBytes("content").length,
                            new String(rs.getBytes("content"), StandardCharsets.UTF_8), rs.getTimestamp("created_at").toInstant()),
                    jobId, afterEventId, bounded);
        }
        int bounded = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT log_id, stream, byte_offset, content, created_at FROM train_job_log WHERE job_id=? AND log_id>? ORDER BY log_id LIMIT ?",
                (rs, row) -> new JobLogEvent(rs.getLong("log_id"), rs.getString("stream"), rs.getLong("byte_offset"),
                        rs.getLong("byte_offset") + rs.getBytes("content").length,
                        new String(rs.getBytes("content"), StandardCharsets.UTF_8), rs.getTimestamp("created_at").toInstant()),
                jobId, afterEventId, bounded);
    }

    public List<JobMetric> metrics(long jobId)
    {
        JobRecord job = jobs.findById(jobId);
        if (job != null && "SSH".equals(job.executionMode()))
            return jdbc.query("SELECT metric_id,metric_name,metric_value,step_number,sampled_at "
                            + "FROM train_ssh_job_metric WHERE job_id=? ORDER BY metric_id DESC LIMIT 500",
                    (rs, row) -> new JobMetric(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                            (Long) rs.getObject(4), rs.getTimestamp(5).toInstant()), jobId);
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
        JobRecord job = jobs.findById(jobId);
        if (job != null && "SSH".equals(job.executionMode()))
            return jdbc.query("SELECT DISTINCT s.gpu_uuid,s.utilization_percent,s.total_memory_mib,s.used_memory_mib,s.sampled_at "
                            + "FROM train_server_gpu_snapshot s JOIN train_server_gpu_lease l "
                            + "ON l.server_id=s.server_id AND l.account_id=s.account_id AND l.gpu_uuid=s.gpu_uuid "
                            + "WHERE l.job_id=? AND s.sampled_at BETWEEN l.acquired_at AND COALESCE(l.released_at,CURRENT_TIMESTAMP(3)) "
                            + "ORDER BY s.sampled_at DESC LIMIT 1000",
                    (rs, row) -> new GpuResourceMetric(rs.getString(1), rs.getInt(2), rs.getLong(3),
                            rs.getLong(4), rs.getTimestamp(5).toInstant()), jobId);
        return jdbc.query("SELECT DISTINCT s.gpu_uuid, s.utilization_percent, s.total_memory_mib, s.used_memory_mib, s.sampled_at "
                + "FROM train_gpu_snapshot s JOIN train_gpu_lease l ON l.gpu_uuid=s.gpu_uuid "
                + "WHERE l.job_id=? AND s.sampled_at BETWEEN l.acquired_at AND COALESCE(l.released_at, CURRENT_TIMESTAMP(3)) "
                + "ORDER BY s.sampled_at DESC LIMIT 1000",
                (rs, row) -> new GpuResourceMetric(rs.getString(1), rs.getInt(2), rs.getLong(3),
                        rs.getLong(4), rs.getTimestamp(5).toInstant()), jobId);
    }

}
