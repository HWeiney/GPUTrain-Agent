package com.ruoyi.train.agent.contract;

import java.util.List;

public record PlannerOutput(int schemaVersion, String objective, String profileId, int maxSteps,
        int gpuCount, List<String> constraints, List<String> assumptions, List<String> risks)
{
    public PlannerOutput
    {
        constraints = List.copyOf(constraints);
        assumptions = List.copyOf(assumptions);
        risks = List.copyOf(risks);
    }
}
