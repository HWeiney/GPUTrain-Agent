package com.ruoyi.train.agent.contract;

public record PlannerInput(int schemaVersion, String requestText)
{
    public PlannerInput(String requestText) { this(1, requestText); }
}
