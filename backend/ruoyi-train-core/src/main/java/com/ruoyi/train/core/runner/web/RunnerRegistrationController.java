package com.ruoyi.train.core.runner.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.runner.service.RunnerEnrollmentService;
import com.ruoyi.train.runner.protocol.RunnerCredentials;
import com.ruoyi.train.runner.protocol.RunnerRegistration;

@RestController
@RequestMapping("/runner-api/v1/register")
public class RunnerRegistrationController
{
    private final RunnerEnrollmentService service;

    public RunnerRegistrationController(RunnerEnrollmentService service)
    {
        this.service = service;
    }

    @Anonymous
    @PostMapping
    public RunnerCredentials register(@RequestBody RunnerRegistration request)
    {
        return service.register(request, Instant.now());
    }
}
