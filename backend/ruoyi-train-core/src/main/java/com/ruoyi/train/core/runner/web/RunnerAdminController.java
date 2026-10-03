package com.ruoyi.train.core.runner.web;

import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.train.core.runner.service.RunnerEnrollmentService;

@RestController
@RequestMapping("/train/runners/enrollments")
public class RunnerAdminController
{
    private final RunnerEnrollmentService service;

    public RunnerAdminController(RunnerEnrollmentService service)
    {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('train:runner:enroll')")
    @PostMapping
    public AjaxResult createEnrollment(@RequestBody CreateEnrollmentRequest request)
    {
        return AjaxResult.success(service.createEnrollment(request.ownerUserId(), request.runnerName(),
                request.linuxUsername(), request.allowedRoot(), request.expiresAt()));
    }

    public record CreateEnrollmentRequest(long ownerUserId, String runnerName, String linuxUsername,
            String allowedRoot, Instant expiresAt)
    {
    }
}
