package com.ruoyi.train.core.runner.web;

import java.time.Duration;
import java.time.Instant;
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
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.runner.service.RunnerHeartbeatService;
import com.ruoyi.train.runner.protocol.RunnerCommandLease;
import com.ruoyi.train.runner.protocol.RunnerCommandReport;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;

@RestController
@RequestMapping("/runner-api/v1")
public class RunnerApiController
{
    private final RunnerHeartbeatService heartbeatService;
    private final RunnerCommandService commandService;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public RunnerApiController(RunnerHeartbeatService heartbeatService, RunnerCommandService commandService,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler)
    {
        this.heartbeatService = heartbeatService;
        this.commandService = commandService;
        this.scheduler = scheduler;
    }

    public RunnerApiController(RunnerHeartbeatService heartbeatService)
    { this(heartbeatService, null, null); }

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
        return commandService.report(runnerId, id, report);
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
