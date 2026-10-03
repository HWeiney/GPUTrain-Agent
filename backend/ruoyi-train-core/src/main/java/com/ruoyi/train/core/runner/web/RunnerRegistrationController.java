package com.ruoyi.train.core.runner.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.core.runner.service.RunnerEnrollmentService;
import com.ruoyi.train.runner.protocol.RunnerCredentials;
import com.ruoyi.train.runner.protocol.RunnerRegistration;

@RestController
@RequestMapping("/runner-api/v1/register")
public class RunnerRegistrationController
{
    private final RunnerEnrollmentService service;
    private final RunnerTransportSecurityGuard transportGuard;

    public RunnerRegistrationController(RunnerEnrollmentService service, RunnerTransportSecurityGuard transportGuard)
    {
        this.service = service;
        this.transportGuard = transportGuard;
    }

    @Anonymous
    @PostMapping
    public RunnerCredentials register(@RequestBody RunnerRegistration request, HttpServletRequest servletRequest)
    {
        transportGuard.assertAllowed(servletRequest.isSecure(), servletRequest.getRemoteAddr());
        return service.register(request, Instant.now());
    }
}
