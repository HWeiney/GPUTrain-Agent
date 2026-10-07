package com.ruoyi.train.agent.policy;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.train.agent.contract.ExecutionPlan;

/** Deterministic boundary for model-generated proposals; it never executes a proposal. */
@Component
public final class ExecutionPolicy
{
    private static final Pattern GPU_UUID = Pattern.compile("GPU-[A-Fa-f0-9-]{36}");

    public PolicyDecision evaluate(boolean canManage, ExecutionPlan plan, Set<String> availableGpuUuids)
    {
        if (!canManage) return PolicyDecision.reject("PERMISSION_DENIED");
        if (plan == null || plan.schemaVersion() != 1) return PolicyDecision.reject("SCHEMA_VERSION_UNSUPPORTED");
        if (!"CREATE_TRAINING_JOB".equals(plan.action())) return PolicyDecision.reject("ACTION_FORBIDDEN");
        if (!"docowl2-srfund".equals(plan.profileId())) return PolicyDecision.reject("PROFILE_NOT_ALLOWED");
        if (plan.maxSteps() < 1 || plan.maxSteps() > 5) return PolicyDecision.reject("STEP_LIMIT_EXCEEDED");
        if (!plan.requiresHumanConfirmation()) return PolicyDecision.reject("HUMAN_CONFIRMATION_REQUIRED");
        if (plan.gpuUuids() == null || availableGpuUuids == null
                || plan.gpuUuids().size() != 3 || new java.util.HashSet<>(plan.gpuUuids()).size() != 3
                || plan.gpuUuids().stream().anyMatch(uuid ->
                uuid == null || !GPU_UUID.matcher(uuid).matches() || !availableGpuUuids.contains(uuid)))
            return PolicyDecision.reject("GPU_NOT_AVAILABLE");
        return new PolicyDecision(true, "DRAFT_REQUIRES_CONFIRMATION", true);
    }
}
