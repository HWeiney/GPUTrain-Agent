package com.ruoyi.train.agent.policy;

public record PolicyDecision(boolean allowed, String code, boolean requiresHumanConfirmation)
{
    public static PolicyDecision reject(String code)
    { return new PolicyDecision(false, code, false); }
}
