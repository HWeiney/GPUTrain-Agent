package com.ruoyi.train.core.runner.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.gpu.service.GpuDeviceCatalogService;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.observability.JobObservabilityService;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.runner.service.RunnerHeartbeatService;
import com.ruoyi.train.core.runner.service.RunnerReconciliationService;
import com.ruoyi.train.runner.protocol.RunnerCommandLease;
import com.ruoyi.train.runner.protocol.RunnerCommandReport;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;
import com.ruoyi.train.runner.protocol.RunnerLogAck;
import com.ruoyi.train.runner.protocol.RunnerLogChunk;
import com.ruoyi.train.runner.protocol.RunnerLogStream;
import com.ruoyi.train.runner.protocol.RunnerProcessSnapshot;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;

@RestController
@RequestMapping("/runner-api/v1")
public class RunnerApiController
{
    private final RunnerHeartbeatService heartbeatService;
    private final RunnerCommandService commandService;
    private final RunnerReconciliationService reconciliationService;
    private final GpuDeviceCatalogService gpuCatalogService;
    private final ScheduledExecutorService scheduler;
    private final JobService jobService;
    private final JobObservabilityService observability;

    @Autowired
    public RunnerApiController(RunnerHeartbeatService heartbeatService, RunnerCommandService commandService,
            RunnerReconciliationService reconciliationService,
            GpuDeviceCatalogService gpuCatalogService,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler,
            JobService jobService, JobObservabilityService observability)
    {
        this.heartbeatService = heartbeatService;
        this.commandService = commandService;
        this.reconciliationService = reconciliationService;
        this.gpuCatalogService = gpuCatalogService;
        this.scheduler = scheduler;
        this.jobService = jobService;
        this.observability = observability;
    }

    public RunnerApiController(RunnerHeartbeatService heartbeatService)
    { this(heartbeatService, null, null, null, null, null, null); }

    public RunnerApiController(RunnerHeartbeatService heartbeatService, RunnerCommandService commandService,
            ScheduledExecutorService scheduler)
    { this(heartbeatService, commandService, null, null, scheduler, null, null); }

    public RunnerApiController(RunnerHeartbeatService heartbeatService, RunnerCommandService commandService,
            RunnerReconciliationService reconciliationService, GpuDeviceCatalogService gpuCatalogService,
            ScheduledExecutorService scheduler, JobService jobService)
    { this(heartbeatService, commandService, reconciliationService, gpuCatalogService, scheduler, jobService, null); }

    @Anonymous
    @PostMapping("/heartbeat")
    public void heartbeat(@RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE)
            String runnerId, @RequestBody RunnerHeartbeat heartbeat)
    {
        heartbeatService.record(runnerId, heartbeat, Instant.now());
    }

    @Anonymous
    @GetMapping("/commands/poll")
    public DeferredResult<ResponseEntity<RunnerCommandLease>> poll(
            @RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @RequestParam(defaultValue = "20") int timeoutSeconds)
    {
        if (timeoutSeconds < 0) throw new IllegalArgumentException("Poll timeout cannot be negative");
        int boundedSeconds = Math.min(timeoutSeconds, 20);
        DeferredResult<ResponseEntity<RunnerCommandLease>> result = new DeferredResult<>(boundedSeconds * 1000L);
        Object deliveryLock = new Object();
        result.onTimeout(() ->
        {
            synchronized (deliveryLock)
            {
                if (!result.isSetOrExpired()) result.setResult(ResponseEntity.noContent().build());
            }
        });
        if (tryLease(runnerId, result, deliveryLock) || boundedSeconds == 0)
        {
            synchronized (deliveryLock)
            {
                if (!result.isSetOrExpired()) result.setResult(ResponseEntity.noContent().build());
            }
            return result;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> tryLease(runnerId, result, deliveryLock),
                250, 250, TimeUnit.MILLISECONDS);
        result.onCompletion(() -> future.cancel(false));
        return result;
    }

    @Anonymous
    @PostMapping("/commands/{id}/reports")
    public RunnerCommandRecord report(
            @RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @PathVariable UUID id, @RequestBody RunnerCommandReport report)
    {
        RunnerCommandRecord result = commandService.report(runnerId, id, report);
        if (jobService != null) jobService.syncCommand(id, Instant.now());
        return result;
    }

    @Anonymous
    @PostMapping("/processes/reconcile")
    public void reconcile(@RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @RequestBody List<RunnerProcessSnapshot> snapshots)
    {
        reconciliationService.reconcile(runnerId, snapshots);
        if (jobService != null)
            snapshots.forEach(snapshot -> jobService.syncCommand(snapshot.commandId(), Instant.now()));
    }

    @Anonymous
    @PostMapping("/gpu-snapshots")
    public void gpuSnapshots(
            @RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @RequestBody GpuDiscoveryReport report)
    {
        gpuCatalogService.ingest(runnerId, report, Instant.now());
    }

    @Anonymous
    @GetMapping("/commands/{id}/logs/offset")
    public long logOffset(@RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @PathVariable UUID id, @RequestParam RunnerLogStream stream)
    {
        return observability.nextOffset(runnerId, id.toString(), stream);
    }

    @Anonymous
    @PostMapping("/commands/{id}/logs")
    public RunnerLogAck logChunk(@RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE) String runnerId,
            @PathVariable UUID id, @RequestBody RunnerLogChunk chunk)
    {
        if (!id.equals(chunk.commandId())) throw new IllegalArgumentException("Command ID mismatch");
        return observability.ingest(runnerId, chunk);
    }

    private boolean tryLease(String runnerId, DeferredResult<ResponseEntity<RunnerCommandLease>> result,
            Object deliveryLock)
    {
        synchronized (deliveryLock)
        {
            if (result.isSetOrExpired()) return true;
            try
            {
                return commandService.leaseNext(runnerId, Instant.now(), Duration.ofSeconds(30))
                        .map(lease -> result.setResult(ResponseEntity.ok(lease))).orElse(false);
            }
            catch (RuntimeException failure)
            {
                result.setErrorResult(failure);
                return true;
            }
        }
    }
}
