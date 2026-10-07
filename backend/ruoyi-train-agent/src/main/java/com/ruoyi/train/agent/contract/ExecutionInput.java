package com.ruoyi.train.agent.contract;

import java.util.Set;

public record ExecutionInput(int schemaVersion, PlannerOutput plan, Set<String> availableGpuUuids)
{
    public ExecutionInput { availableGpuUuids = Set.copyOf(availableGpuUuids); }
}
