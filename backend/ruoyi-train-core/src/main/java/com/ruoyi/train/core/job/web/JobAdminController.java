package com.ruoyi.train.core.job.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.job.service.JobSubmission;

/**
 * Administrative API for training jobs. The authenticated user is always the owner;
 * the client cannot claim an arbitrary user id or GPU. Non-owners cannot view or stop
 * another user's job unless they hold the administrator role.
 */
@RestController
@RequestMapping("/train/jobs")
public class JobAdminController
{
    private final JobService jobService;

    public JobAdminController(JobService jobService)
    {
        this.jobService = jobService;
    }

    @PreAuthorize("@ss.hasPermi('train:job:manage')")
    @PostMapping
    public AjaxResult create(@RequestBody CreateJobRequest request)
    {
        long owner = SecurityUtils.getUserId();
        JobSubmission submission = new JobSubmission(owner, request.gpuCount(),
                request.minMemoryMiB(), setOrEmpty(request.allowedGpuUuids()),
                setOrEmpty(request.deniedGpuUuids()), request.requestKey(),
                request.workingDirectory(), request.executable(),
                listOrEmpty(request.arguments()),
                mapOrEmpty(request.environment()), request.timeoutSeconds());
        JobRecord job = jobService.submit(submission, Instant.now());
        return AjaxResult.success(job);
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping
    public AjaxResult list()
    {
        long userId = SecurityUtils.getUserId();
        return AjaxResult.success(jobService.list(userId, SecurityUtils.isAdmin(userId)));
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping("/{jobId}")
    public AjaxResult get(@PathVariable long jobId)
    {
        JobRecord job = jobService.getOwned(jobId, SecurityUtils.getUserId(),
                SecurityUtils.isAdmin(SecurityUtils.getUserId()));
        return AjaxResult.success(job);
    }

    @PreAuthorize("@ss.hasPermi('train:job:manage')")
    @PostMapping("/{jobId}/stop")
    public AjaxResult stop(@PathVariable long jobId)
    {
        long userId = SecurityUtils.getUserId();
        jobService.getOwned(jobId, userId, SecurityUtils.isAdmin(userId));
        return AjaxResult.success(jobService.stop(jobId, Instant.now()));
    }

    private static Set<String> setOrEmpty(Set<String> values)
    {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static List<String> listOrEmpty(List<String> values)
    {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, String> mapOrEmpty(Map<String, String> values)
    {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    public record CreateJobRequest(int gpuCount, long minMemoryMiB,
            Set<String> allowedGpuUuids, Set<String> deniedGpuUuids,
            String requestKey, String workingDirectory, String executable,
            List<String> arguments, Map<String, String> environment,
            long timeoutSeconds)
    {
    }
}
