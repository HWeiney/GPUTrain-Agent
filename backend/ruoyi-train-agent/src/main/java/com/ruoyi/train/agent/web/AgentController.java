package com.ruoyi.train.agent.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.agent.contract.DiagnosisInput;
import com.ruoyi.train.agent.contract.ExecutionInput;
import com.ruoyi.train.agent.contract.PlannerInput;
import com.ruoyi.train.agent.contract.PlannerOutput;
import com.ruoyi.train.agent.service.AgentWorkflowService;
import com.ruoyi.train.core.gpu.domain.GpuDeviceView;
import com.ruoyi.train.core.gpu.mapper.GpuDeviceMapper;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.observability.JobLogEvent;
import com.ruoyi.train.core.observability.JobObservabilityService;

@RestController
@RequestMapping("/train/agents")
public class AgentController
{
    private final AgentWorkflowService workflow;
    private final JobService jobs;
    private final JobObservabilityService observations;
    private final GpuDeviceMapper devices;

    public AgentController(AgentWorkflowService workflow, JobService jobs,
            JobObservabilityService observations, GpuDeviceMapper devices)
    {
        this.workflow = workflow; this.jobs = jobs;
        this.observations = observations; this.devices = devices;
    }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @PostMapping("/plan")
    public AjaxResult plan(@RequestBody PlannerInput input)
    { return AjaxResult.success(workflow.plan(input)); }

    @PreAuthorize("@ss.hasPermi('train:job:view')")
    @PostMapping("/diagnose")
    public AjaxResult diagnose(@RequestBody DiagnoseRequest request)
    {
        long userId = SecurityUtils.getUserId();
        JobRecord job = jobs.getOwned(request.jobId(), userId, SecurityUtils.isAdmin(userId));
        return AjaxResult.success(workflow.diagnose(diagnosisInput(job, request.afterEventId())));
    }

    @PreAuthorize("@ss.hasPermi('train:job:manage')")
    @PostMapping("/execution-draft")
    public AjaxResult executionDraft(@RequestBody ExecutionDraftRequest request)
    {
        Set<String> available = devices.listDevices().stream().filter(this::availableForDraft)
                .map(GpuDeviceView::gpuUuid).collect(Collectors.toUnmodifiableSet());
        return AjaxResult.success(workflow.draftExecution(
                new ExecutionInput(1, request.plan(), available), true));
    }

    private DiagnosisInput diagnosisInput(JobRecord job, long afterEventId)
    {
        if (afterEventId < 0) throw new IllegalArgumentException("afterEventId must be non-negative");
        List<JobLogEvent> events = observations.logs(job.jobId(), afterEventId, 20);
        List<String> logs = new ArrayList<>();
        for (JobLogEvent event : events)
        {
            String content = event.text();
            logs.add(content.length() <= 4000 ? content : content.substring(content.length() - 4000));
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        observations.metrics(job.jobId()).forEach(metric -> metrics.putIfAbsent(metric.name(), metric.value()));
        List<String> alerts = observations.alerts(job.jobId()).stream()
                .map(alert -> alert.ruleCode() + ": " + alert.message()).limit(20).toList();
        Integer exitCode = "SUCCEEDED".equals(job.status()) ? 0 : null;
        return new DiagnosisInput(1, job.jobId(), job.status(), exitCode, logs, metrics, alerts);
    }

    private boolean availableForDraft(GpuDeviceView gpu)
    {
        return gpu.schedulable() && !gpu.hasActiveLease()
                && gpu.totalMemoryMiB() - gpu.usedMemoryMiB() >= 70_000;
    }

    public record DiagnoseRequest(long jobId, long afterEventId)
    { public DiagnoseRequest(long jobId) { this(jobId, 0); } }
    public record ExecutionDraftRequest(PlannerOutput plan) { }
}
