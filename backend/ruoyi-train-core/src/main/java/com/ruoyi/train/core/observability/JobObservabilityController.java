package com.ruoyi.train.core.observability;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.service.JobService;

@RestController
@RequestMapping("/train/jobs/{jobId}")
public class JobObservabilityController
{
    private final JobService jobs;
    private final JobObservabilityService observability;
    private final ScheduledExecutorService scheduler;

    public JobObservabilityController(JobService jobs, JobObservabilityService observability,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler)
    { this.jobs = jobs; this.observability = observability; this.scheduler = scheduler; }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping("/logs")
    public AjaxResult logs(@PathVariable long jobId, @RequestParam(defaultValue = "0") long afterEventId,
            @RequestParam(defaultValue = "200") int limit)
    {
        authorize(jobId);
        if (afterEventId < 0) throw new IllegalArgumentException("afterEventId must be non-negative");
        return AjaxResult.success(observability.logs(jobId, afterEventId, limit));
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId)
    {
        authorize(jobId);
        long cursor = parseCursor(lastEventId);
        SseEmitter emitter = new SseEmitter(15_000L);
        AtomicLong delivered = new AtomicLong(cursor);
        ScheduledFuture<?> poll = scheduler.scheduleAtFixedRate(() ->
        {
            try
            {
                List<JobLogEvent> events = observability.logs(jobId, delivered.get(), 100);
                for (JobLogEvent event : events)
                {
                    emitter.send(SseEmitter.event().id(Long.toString(event.eventId())).name("log").data(event));
                    delivered.set(event.eventId());
                }
            }
            catch (IOException | RuntimeException failure) { emitter.completeWithError(failure); }
        }, 0, 500, TimeUnit.MILLISECONDS);
        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> poll.cancel(false));
        emitter.onError(ignored -> poll.cancel(false));
        return emitter;
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping("/metrics")
    public AjaxResult metrics(@PathVariable long jobId)
    { authorize(jobId); return AjaxResult.success(observability.metrics(jobId)); }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping("/alerts")
    public AjaxResult alerts(@PathVariable long jobId)
    { authorize(jobId); return AjaxResult.success(observability.alerts(jobId)); }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping("/resources")
    public AjaxResult resources(@PathVariable long jobId)
    { authorize(jobId); return AjaxResult.success(observability.gpuResources(jobId)); }

    private void authorize(long jobId)
    {
        long userId = SecurityUtils.getUserId();
        jobs.getOwned(jobId, userId, SecurityUtils.isAdmin(userId));
    }

    private static long parseCursor(String value)
    {
        if (value == null || value.isBlank()) return 0;
        try { long cursor = Long.parseLong(value); if (cursor < 0) throw new NumberFormatException(); return cursor; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid Last-Event-ID"); }
    }
}
