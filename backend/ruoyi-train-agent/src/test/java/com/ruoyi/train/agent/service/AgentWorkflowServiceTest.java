package com.ruoyi.train.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.agent.contract.DiagnosisInput;
import com.ruoyi.train.agent.contract.ExecutionInput;
import com.ruoyi.train.agent.contract.PlannerInput;
import com.ruoyi.train.agent.model.AgentRole;
import com.ruoyi.train.agent.model.StructuredAgentModel;
import com.ruoyi.train.agent.policy.ExecutionPolicy;
import org.junit.jupiter.api.Test;

class AgentWorkflowServiceTest
{
    private static final String GPU_1 = "GPU-11111111-1111-1111-1111-111111111111";
    private static final String GPU_2 = "GPU-22222222-2222-2222-2222-222222222222";
    private static final String GPU_3 = "GPU-33333333-3333-3333-3333-333333333333";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plannerProducesVersionedPlanWithinSupportedProfile()
    {
        var service = service(AgentRole.PLANNER, """
                {"schemaVersion":1,"objective":"验证训练","profileId":"docowl2-srfund","maxSteps":2,
                 "gpuCount":3,"constraints":["保留 checkpoint"],"assumptions":[],"risks":[]}
                """);

        var result = service.plan(new PlannerInput("运行两步验证"));

        assertEquals("docowl2-srfund", result.profileId());
        assertEquals(2, result.maxSteps());
    }

    @Test
    void diagnosisReturnsEvidenceBoundStructuredResult()
    {
        var service = service(AgentRole.DIAGNOSIS, """
                {"schemaVersion":1,"category":"NCCL_ERROR","confidence":0.92,
                 "evidence":["NCCL watchdog timeout"],"probableCauses":["通信超时"],"recommendations":["检查网络状态"]}
                """);
        var input = new DiagnosisInput(1, 7, "FAILED", 1,
                java.util.List.of("NCCL watchdog timeout"), java.util.Map.of(), java.util.List.of());

        var result = service.diagnose(input);

        assertEquals("NCCL_ERROR", result.category());
        assertEquals(input.logEvidence(), result.evidence());
    }

    @Test
    void executionOutputIsOnlyAnApprovalRequiredDraft()
    {
        var service = service(AgentRole.EXECUTION, executionJson(true));
        var input = new ExecutionInput(1, plannerOutput(), java.util.Set.of(GPU_1, GPU_2, GPU_3));

        var result = service.draftExecution(input, true);

        assertTrue(result.policyDecision().allowed());
        assertTrue(result.plan().requiresHumanConfirmation());
        assertEquals("DRAFT_REQUIRES_CONFIRMATION", result.policyDecision().code());
    }

    @Test
    void rejectsUnknownFieldsAndUnsafeModelOutput()
    {
        var extra = service(AgentRole.PLANNER, """
                {"schemaVersion":1,"objective":"验证","profileId":"docowl2-srfund","maxSteps":1,
                 "gpuCount":3,"constraints":[],"assumptions":[],"risks":[],"shell":"rm -rf /"}
                """);
        assertThrows(IllegalArgumentException.class, () -> extra.plan(new PlannerInput("验证")));

        var shell = service(AgentRole.EXECUTION, executionJson("RUN_SHELL", true));
        var result = shell.draftExecution(new ExecutionInput(1, plannerOutput(),
                java.util.Set.of(GPU_1, GPU_2, GPU_3)), true);
        assertEquals("ACTION_FORBIDDEN", result.policyDecision().code());
    }

    @Test
    void rejectsOutOfRangePlannerAndDiagnosisValues()
    {
        var badVersion = service(AgentRole.PLANNER, """
                {"schemaVersion":2,"objective":"验证","profileId":"docowl2-srfund","maxSteps":1,
                 "gpuCount":3,"constraints":[],"assumptions":[],"risks":[]}
                """);
        assertThrows(IllegalArgumentException.class, () -> badVersion.plan(new PlannerInput("验证")));
        var badPlan = service(AgentRole.PLANNER, """
                {"schemaVersion":1,"objective":"验证","profileId":"docowl2-srfund","maxSteps":999,
                 "gpuCount":3,"constraints":[],"assumptions":[],"risks":[]}
                """);
        assertThrows(IllegalArgumentException.class, () -> badPlan.plan(new PlannerInput("验证")));
        var badDiagnosis = service(AgentRole.DIAGNOSIS, """
                {"schemaVersion":1,"category":"OTHER","confidence":4.2,
                 "evidence":[],"probableCauses":[],"recommendations":[]}
                """);
        assertThrows(IllegalArgumentException.class, () -> badDiagnosis.diagnose(
                new DiagnosisInput(1, 1, "FAILED", 1, java.util.List.of(), java.util.Map.of(), java.util.List.of())));
    }

    @Test
    void rejectsOversizedClientPlanBeforeSendingItToExecutionModel()
    {
        var service = service(AgentRole.EXECUTION, executionJson(true));
        var oversized = new com.ruoyi.train.agent.contract.PlannerOutput(1, "x".repeat(5001),
                "docowl2-srfund", 1, 3, java.util.List.of(), java.util.List.of(), java.util.List.of());

        assertThrows(IllegalArgumentException.class, () -> service.draftExecution(
                new ExecutionInput(1, oversized, java.util.Set.of(GPU_1, GPU_2, GPU_3)), true));
    }

    @Test
    void promptsRequireOneJsonObjectWithExactRoleFields()
    {
        var prompts = new java.util.EnumMap<AgentRole, String>(AgentRole.class);
        StructuredAgentModel capture = (role, prompt, input) -> {
            prompts.put(role, prompt);
            return read(switch (role)
            {
                case PLANNER -> """
                        {"schemaVersion":1,"objective":"验证","profileId":"docowl2-srfund","maxSteps":1,
                         "gpuCount":3,"constraints":[],"assumptions":[],"risks":[]}
                        """;
                case DIAGNOSIS -> """
                        {"schemaVersion":1,"category":"OTHER","confidence":0.5,"evidence":[],
                         "probableCauses":[],"recommendations":[]}
                        """;
                case EXECUTION -> executionJson(true);
            });
        };
        var service = new AgentWorkflowService(mapper, capture, new ExecutionPolicy());

        service.plan(new PlannerInput("验证"));
        service.diagnose(new DiagnosisInput(1, 1, "FAILED", 1, java.util.List.of(), java.util.Map.of(), java.util.List.of()));
        service.draftExecution(new ExecutionInput(1, plannerOutput(), java.util.Set.of(GPU_1, GPU_2, GPU_3)), true);

        assertPromptContract(prompts.get(AgentRole.PLANNER), "schemaVersion", "objective", "profileId", "maxSteps",
                "gpuCount", "constraints", "assumptions", "risks");
        assertPromptContract(prompts.get(AgentRole.DIAGNOSIS), "schemaVersion", "category", "confidence", "evidence",
                "probableCauses", "recommendations");
        assertTrue(prompts.get(AgentRole.DIAGNOSIS).contains("CUDA_OOM, NAN_LOSS, NCCL_ERROR, CONFIGURATION, DATA_PIPELINE, SUCCESS, OTHER"));
        assertTrue(prompts.get(AgentRole.DIAGNOSIS).contains("exact strings copied verbatim"));
        assertPromptContract(prompts.get(AgentRole.EXECUTION), "schemaVersion", "action", "profileId", "maxSteps",
                "gpuUuids", "requiresHumanConfirmation");
    }

    private void assertPromptContract(String prompt, String... fields)
    {
        assertTrue(prompt.toLowerCase().contains("json object"));
        for (String field : fields) assertTrue(prompt.contains(field), "Prompt is missing field " + field);
    }

    private AgentWorkflowService service(AgentRole expectedRole, String json)
    {
        StructuredAgentModel fixed = (role, system, input) -> {
            assertEquals(expectedRole, role);
            return read(json);
        };
        return new AgentWorkflowService(mapper, fixed, new ExecutionPolicy());
    }

    private JsonNode read(String json)
    {
        try { return mapper.readTree(json); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private String executionJson(boolean confirmation) { return executionJson("CREATE_TRAINING_JOB", confirmation); }

    private String executionJson(String action, boolean confirmation)
    {
        return "{\"schemaVersion\":1,\"action\":\"" + action + "\",\"profileId\":\"docowl2-srfund\","
                + "\"maxSteps\":1,\"gpuUuids\":[\"" + GPU_1 + "\",\"" + GPU_2 + "\",\"" + GPU_3
                + "\"],\"requiresHumanConfirmation\":" + confirmation + "}";
    }

    private com.ruoyi.train.agent.contract.PlannerOutput plannerOutput()
    {
        return new com.ruoyi.train.agent.contract.PlannerOutput(1, "验证", "docowl2-srfund", 1, 3,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
}
