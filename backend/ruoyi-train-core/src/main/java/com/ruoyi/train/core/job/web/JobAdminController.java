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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.job.service.JobSubmission;
import com.ruoyi.train.core.server.job.SshJobDispatcher;

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
    private final SshJobDispatcher sshJobDispatcher;

    public JobAdminController(JobService jobService, SshJobDispatcher sshJobDispatcher)
    {
        this.jobService = jobService;
        this.sshJobDispatcher = sshJobDispatcher;
    }

    @PreAuthorize("@ss.hasPermi('train:job:manage')")
    @PostMapping
    public AjaxResult create(@RequestBody CreateJobRequest request)
    {
        long owner = SecurityUtils.getUserId();
        if (!"docowl2-srfund".equals(request.profileId()))
            throw new IllegalArgumentException("Unknown training profile");
        if (request.maxSteps() < 1 || request.maxSteps() > 5)
            throw new IllegalArgumentException("Short smoke test maxSteps must be between 1 and 5");
        if (request.requestKey() == null || request.requestKey().isBlank())
            throw new IllegalArgumentException("requestKey is required");
        JobSubmission submission = toSubmission(owner, request);
        JobRecord job = jobService.submit(submission, Instant.now());
        return AjaxResult.success(job);
    }

    static JobSubmission toSubmission(long owner, CreateJobRequest request)
    {
        Set<String> allowedUuids = request.allowedGpuUuids() == null
                ? Set.of() : Set.copyOf(request.allowedGpuUuids());
        if (allowedUuids.size() != 3
                || allowedUuids.stream().anyMatch(uuid -> !uuid.matches("GPU-[A-Fa-f0-9-]+")))
            throw new IllegalArgumentException("GPU allowlist must contain exactly three GPU UUIDs");
        Map<String, String> environment = Map.of(
                "MODEL_PATH", "/model/hanw/ms-swift-imp3-research/pretrained-imp/DocOwl2",
                "PYTHON_BIN", "/home/hanw/.conda/envs/swift/bin/python",
                "NPROC_PER_NODE", "3", "MAX_STEPS", Integer.toString(request.maxSteps()),
                "RUN_MODE", "foreground", "AUTO_EVAL", "False", "TRAIN_EVAL_STRATEGY", "no",
                "RUN_ID", "gputrain-" + request.requestKey());
        if ((request.serverId() == null) != (request.accountId() == null))
            throw new IllegalArgumentException("Server and account are required together");
        return new JobSubmission(owner, 3, 0, allowedUuids, Set.of(), request.requestKey(),
                "/data/hanw/hw_code/gputrain-test", "/usr/bin/bash",
                List.of("/model/hanw/ms-swift-imp3-research/fintune_val_shell/run_swift_docowl2_logidoc.sh",
                        "/model/hanw/ms-swift-imp3-research/configs/ocr_evidence/docowl2_srfund.env"),
                environment, 3600, 70000, true, request.serverId(), request.accountId());
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @GetMapping
    public AjaxResult list(@RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeJobId)
    {
        long userId = SecurityUtils.getUserId();
        return AjaxResult.success(jobService.list(userId, SecurityUtils.isAdmin(userId), limit, beforeJobId));
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
        JobRecord job = jobService.getOwned(jobId, userId, SecurityUtils.isAdmin(userId));
        if ("SSH".equals(job.executionMode())) return AjaxResult.success(sshJobDispatcher.stop(jobId, Instant.now()));
        return AjaxResult.success(jobService.stop(jobId, Instant.now()));
    }

    @PreAuthorize("@ss.hasPermi('train:job:manage')")
    @PostMapping("/{jobId}/cancel")
    public AjaxResult cancel(@PathVariable long jobId)
    {
        long userId = SecurityUtils.getUserId();
        jobService.getOwned(jobId, userId, SecurityUtils.isAdmin(userId));
        return AjaxResult.success(jobService.cancel(jobId, Instant.now()));
    }

    public record CreateJobRequest(String profileId, int maxSteps, String requestKey,
            Long serverId, Long accountId, Set<String> allowedGpuUuids)
    {
        public CreateJobRequest(String profileId, int maxSteps, String requestKey, Set<String> allowedGpuUuids)
        { this(profileId, maxSteps, requestKey, null, null, allowedGpuUuids); }
    }
}
