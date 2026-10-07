package com.ruoyi.train.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.agent.contract.ExecutionPlan;

class ExecutionPolicyTest
{
    private static final Set<String> AVAILABLE = Set.of(
            "GPU-11111111-1111-1111-1111-111111111111",
            "GPU-22222222-2222-2222-2222-222222222222",
            "GPU-33333333-3333-3333-3333-333333333333");
    private final ExecutionPolicy policy = new ExecutionPolicy();

    @Test
    void validPlanIsOnlyAnApprovalRequiredDraft()
    {
        var decision = policy.evaluate(true, plan("CREATE_TRAINING_JOB", "docowl2-srfund", 1, true,
                List.copyOf(AVAILABLE)), AVAILABLE);

        assertTrue(decision.allowed());
        assertEquals("DRAFT_REQUIRES_CONFIRMATION", decision.code());
        assertTrue(decision.requiresHumanConfirmation());
    }

    @Test
    void rejectsUserWithoutManagePermission()
    {
        var decision = policy.evaluate(false, validPlan(), AVAILABLE);

        assertEquals("PERMISSION_DENIED", decision.code());
    }

    @Test
    void rejectsDraftWithoutExplicitHumanConfirmation()
    {
        var decision = policy.evaluate(true, plan("CREATE_TRAINING_JOB", "docowl2-srfund", 1, false,
                List.copyOf(AVAILABLE)), AVAILABLE);

        assertEquals("HUMAN_CONFIRMATION_REQUIRED", decision.code());
    }

    @Test
    void rejectsShellActionsAndOutOfRangeSteps()
    {
        assertEquals("ACTION_FORBIDDEN", policy.evaluate(true,
                plan("RUN_SHELL", "docowl2-srfund", 1, true, List.copyOf(AVAILABLE)), AVAILABLE).code());
        assertEquals("STEP_LIMIT_EXCEEDED", policy.evaluate(true,
                plan("CREATE_TRAINING_JOB", "docowl2-srfund", 6, true, List.copyOf(AVAILABLE)), AVAILABLE).code());
    }

    @Test
    void rejectsGpuOutsideServerAllowlist()
    {
        var requested = List.of("GPU-11111111-1111-1111-1111-111111111111",
                "GPU-22222222-2222-2222-2222-222222222222", "GPU-99999999-9999-9999-9999-999999999999");

        assertEquals("GPU_NOT_AVAILABLE", policy.evaluate(true,
                plan("CREATE_TRAINING_JOB", "docowl2-srfund", 1, true, requested), AVAILABLE).code());
    }

    @Test
    void rejectsRepeatedGpuUuidInsteadOfTreatingItAsThreeDevices()
    {
        var requested = List.of("GPU-11111111-1111-1111-1111-111111111111",
                "GPU-11111111-1111-1111-1111-111111111111",
                "GPU-22222222-2222-2222-2222-222222222222");

        assertEquals("GPU_NOT_AVAILABLE", policy.evaluate(true,
                plan("CREATE_TRAINING_JOB", "docowl2-srfund", 1, true, requested), AVAILABLE).code());
    }

    private ExecutionPlan validPlan()
    { return plan("CREATE_TRAINING_JOB", "docowl2-srfund", 1, true, List.copyOf(AVAILABLE)); }

    private ExecutionPlan plan(String action, String profile, int steps, boolean confirmation, List<String> gpus)
    { return new ExecutionPlan(1, action, profile, steps, gpus, confirmation); }
}
