package com.ruoyi.train.agent.policy;

import com.ruoyi.train.agent.contract.ExecutionPlan;

public record ExecutionDraft(ExecutionPlan plan, PolicyDecision policyDecision)
{
}
