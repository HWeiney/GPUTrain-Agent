package com.ruoyi.train.agent.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.agent.contract.CatalogAction;
import com.ruoyi.train.agent.contract.CatalogParam;
import com.ruoyi.train.agent.contract.DiagnosisInput;
import com.ruoyi.train.agent.contract.ExecutionInput;
import com.ruoyi.train.agent.contract.MemoryTurn;
import com.ruoyi.train.agent.contract.PlannerInput;
import com.ruoyi.train.agent.contract.PlannerOutput;
import com.ruoyi.train.agent.contract.ProposalDraftInput;
import com.ruoyi.train.agent.service.AgentWorkflowService;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.observability.JobLogEvent;
import com.ruoyi.train.core.observability.JobObservabilityService;
import com.ruoyi.train.core.server.agentmemory.AgentMemoryService;
import com.ruoyi.train.core.server.agentmemory.domain.AgentMemoryRecord;
import com.ruoyi.train.core.server.agentmemory.domain.MemoryTurnView;
import com.ruoyi.train.core.server.proposal.CommandActionRegistry;
import com.ruoyi.train.core.server.proposal.ProposalService;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService;
import com.ruoyi.train.core.server.gpu.SshGpuLeaseMapper;

@RestController
@RequestMapping("/train/agents")
public class AgentController
{
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentWorkflowService workflow;
    private final JobService jobs;
    private final JobObservabilityService observations;
    private final ServerDeviceQueryService devices;
    private final SshGpuLeaseMapper leases;
    private final ProposalService proposals;
    private final CommandActionRegistry registry;
    private final AgentMemoryService memory;
    private final ObjectMapper json;

    public AgentController(AgentWorkflowService workflow, JobService jobs,
            JobObservabilityService observations, ServerDeviceQueryService devices, SshGpuLeaseMapper leases,
            ProposalService proposals, CommandActionRegistry registry, AgentMemoryService memory, ObjectMapper json)
    {
        this.workflow = workflow; this.jobs = jobs;
        this.observations = observations; this.devices = devices;
        this.leases = leases; this.proposals = proposals; this.registry = registry;
        this.memory = memory; this.json = json;
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
        if (request.serverId() == null || request.accountId() == null)
            throw new IllegalArgumentException("SSH server and account are required");
        Set<String> available = devices.list(request.serverId(), request.accountId(), SecurityUtils.getUserId())
                .stream().filter(gpu -> availableForDraft(gpu, request.serverId()))
                .map(ServerDeviceQueryService.DeviceView::gpuUuid).collect(Collectors.toUnmodifiableSet());
        return AjaxResult.success(workflow.draftExecution(
                new ExecutionInput(1, request.plan(), available), true));
    }

    /**
     * P4 Agent 提案起草：动作目录 + 用户意图 + 会话长记忆（最近 8 轮）→ 模型选择动作/参数/依据 →
     * 一步创建 source=AGENT 的 PENDING 提案（人工确认 = 通道 B 的批准/拒绝闭环），并追加一轮记忆。
     * 发给模型的只有目录元数据、意图文本与记忆轮次（意图/动作/结果/输出摘要），服务器/账户/凭证不进模型输入。
     */
    @PreAuthorize("@ss.hasPermi('train:proposal:execute')")
    @PostMapping("/proposal-draft")
    public AjaxResult proposalDraft(@RequestBody ProposalDraftRequest request)
    {
        if (request == null || request.serverId() == null || request.accountId() == null
                || request.intentText() == null || request.intentText().isBlank())
            throw new IllegalArgumentException("intentText, serverId and accountId are required");
        long userId = SecurityUtils.getUserId();
        String intentText = request.intentText().trim();
        List<CatalogAction> catalog = registry.catalog().stream()
                .map(action -> new CatalogAction(action.name(), action.description(),
                        action.params().stream()
                                .map(param -> new CatalogParam(param.name(), param.type(),
                                        param.required(), param.description())).toList()))
                .toList();
        List<MemoryTurn> history = memory.history(userId, request.serverId(), AgentMemoryService.MAX_HISTORY_TURNS)
                .stream().map(turn -> new MemoryTurn(turn.intentText(), turn.action(),
                        turn.status(), turn.exitCode(), turn.outputExcerpt())).toList();
        var output = workflow.draftProposal(new ProposalDraftInput(1, intentText, catalog, history));
        CommandProposalRecord proposal = proposals.create(userId, request.serverId(),
                request.accountId(), output.action(), output.args(), "AGENT", output.rationale());
        recordMemoryTurn(userId, request.serverId(), intentText, output, proposal);
        return AjaxResult.success(proposal);
    }

    /** 本人在指定服务器上的 Agent 会话记忆（时间正序，含 JOIN 提案的实时结果）。 */
    @PreAuthorize("@ss.hasPermi('train:proposal:view')")
    @GetMapping("/memory")
    public AjaxResult memory(@RequestParam long serverId)
    {
        if (serverId <= 0) throw new IllegalArgumentException("serverId is required");
        return AjaxResult.success(memory.history(SecurityUtils.getUserId(), serverId,
                AgentMemoryService.MAX_HISTORY_TURNS));
    }

    /** 记忆落库失败只降级（log warn），不影响已创建提案，避免用户误重试造成重复提案。 */
    private void recordMemoryTurn(long userId, Long serverId, String intentText,
            com.ruoyi.train.agent.contract.ProposalDraftOutput output, CommandProposalRecord proposal)
    {
        try
        {
            JsonNode args = output.args();
            memory.record(new AgentMemoryRecord(UUID.randomUUID().toString(), userId, serverId,
                    intentText, output.action(), args == null ? "{}" : json.writeValueAsString(args),
                    output.rationale(), proposal.proposalId(), Instant.now()));
        }
        catch (RuntimeException | java.io.IOException failure)
        {
            log.warn("Agent memory turn persist failed for proposal {}", proposal.proposalId(), failure);
        }
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

    private boolean availableForDraft(ServerDeviceQueryService.DeviceView gpu, long serverId)
    {
        return gpu.schedulable() && leases.findActiveByServerGpu(serverId, gpu.gpuUuid()) == null
                && gpu.totalMemoryMiB() - gpu.usedMemoryMiB() >= 70_000;
    }

    public record DiagnoseRequest(long jobId, long afterEventId)
    { public DiagnoseRequest(long jobId) { this(jobId, 0); } }
    public record ExecutionDraftRequest(PlannerOutput plan, Long serverId, Long accountId) { }
    public record ProposalDraftRequest(Long serverId, Long accountId, String intentText) { }
}
