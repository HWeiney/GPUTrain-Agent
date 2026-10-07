package com.ruoyi.train.agent.contract;

import java.util.List;

public record ExecutionPlan(int schemaVersion, String action, String profileId, int maxSteps,
        List<String> gpuUuids, boolean requiresHumanConfirmation)
{
    public ExecutionPlan { gpuUuids = List.copyOf(gpuUuids); }
}
