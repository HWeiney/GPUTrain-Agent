package com.ruoyi.train.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.observability.JobObservabilityService;

/** Owner-scoped, read-only data boundary used by MCP tools. */
@Service
public class McpQueryService
{
    private final JobService jobs;
    private final JobObservabilityService observability;

    public McpQueryService(JobService jobs, JobObservabilityService observability)
    { this.jobs = Objects.requireNonNull(jobs); this.observability = Objects.requireNonNull(observability); }

    public List<Map<String, Object>> listMyExperiments(long userId)
    {
        return jobs.list(userId, false).stream().limit(100).map(McpQueryService::safeSummary).toList();
    }

    public Map<String, Object> getMyExperiment(long userId, long jobId)
    { return safeSummary(jobs.getOwned(jobId, userId, false)); }

    public List<?> logs(long userId, long jobId, long afterEventId, int limit)
    {
        if (afterEventId < 0) throw new IllegalArgumentException("afterEventId must be non-negative");
        requireOwned(userId, jobId);
        return observability.logs(jobId, afterEventId, Math.min(Math.max(limit, 1), 100));
    }

    public List<?> metrics(long userId, long jobId)
    { requireOwned(userId, jobId); return first100(observability.metrics(jobId)); }

    public List<?> alerts(long userId, long jobId)
    { requireOwned(userId, jobId); return first100(observability.alerts(jobId)); }

    public List<?> resources(long userId, long jobId)
    { requireOwned(userId, jobId); return first100(observability.gpuResources(jobId)); }

    private static List<?> first100(List<?> records)
    { return records.size() <= 100 ? records : records.subList(0, 100); }

    private void requireOwned(long userId, long jobId)
    { jobs.getOwned(jobId, userId, false); }

    private static Map<String, Object> safeSummary(JobRecord job)
    {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", job.jobId());
        summary.put("jobUuid", job.jobUuid());
        summary.put("status", job.status());
        summary.put("gpuCount", job.gpuCount());
        summary.put("createdAt", job.createdAt());
        summary.put("updatedAt", job.updatedAt());
        return Map.copyOf(summary);
    }
}
