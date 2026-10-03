package com.ruoyi.train.core.runner.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.runner.service.RunnerHeartbeatService;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;

@RestController
@RequestMapping("/runner-api/v1")
public class RunnerApiController
{
    private final RunnerHeartbeatService heartbeatService;

    public RunnerApiController(RunnerHeartbeatService heartbeatService)
    {
        this.heartbeatService = heartbeatService;
    }

    @Anonymous
    @PostMapping("/heartbeat")
    public void heartbeat(@RequestAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE)
            String runnerId, @RequestBody RunnerHeartbeat heartbeat)
    {
        heartbeatService.record(runnerId, heartbeat, Instant.now());
    }
}
