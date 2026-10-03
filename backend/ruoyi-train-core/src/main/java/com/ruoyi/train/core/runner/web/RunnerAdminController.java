package com.ruoyi.train.core.runner.web;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.runner.service.RunnerEnrollmentService;
import com.ruoyi.train.runner.protocol.ProcessSpec;

@RestController
@RequestMapping("/train/runners")
public class RunnerAdminController
{
    private final RunnerEnrollmentService service;
    private final RunnerTransportSecurityGuard transportGuard;
    private final RunnerCommandService commandService;

    @Autowired
    public RunnerAdminController(RunnerEnrollmentService service, RunnerTransportSecurityGuard transportGuard,
            RunnerCommandService commandService)
    {
        this.service = service;
        this.transportGuard = transportGuard;
        this.commandService = commandService;
    }

    public RunnerAdminController(RunnerEnrollmentService service, RunnerTransportSecurityGuard transportGuard)
    { this(service, transportGuard, null); }

    @PreAuthorize("@ss.hasPermi('train:runner:enroll')")
    @PostMapping("/enrollments")
    public AjaxResult createEnrollment(@RequestBody CreateEnrollmentRequest request, HttpServletRequest servletRequest)
    {
        transportGuard.assertAllowed(servletRequest.isSecure(), servletRequest.getRemoteAddr());
        return AjaxResult.success(service.createEnrollment(request.ownerUserId(), request.runnerName(),
                request.linuxUsername(), request.allowedRoot(), request.expiresAt(), request.idempotencyKey()));
    }

    @PreAuthorize("@ss.hasPermi('train:runner:command')")
    @PostMapping("/{runnerId}/fixture-commands")
    public AjaxResult createFixtureCommand(@PathVariable String runnerId, @RequestBody ProcessSpec spec)
    {
        return AjaxResult.success(commandService.createFixtureCommand(runnerId, spec, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('train:runner:command')")
    @PostMapping("/{runnerId}/commands/{id}/stop")
    public AjaxResult requestStop(@PathVariable String runnerId, @PathVariable UUID id)
    {
        return AjaxResult.success(commandService.requestStop(runnerId, id));
    }

    public record CreateEnrollmentRequest(long ownerUserId, String runnerName, String linuxUsername,
            String allowedRoot, Instant expiresAt, String idempotencyKey)
    {
    }
}
