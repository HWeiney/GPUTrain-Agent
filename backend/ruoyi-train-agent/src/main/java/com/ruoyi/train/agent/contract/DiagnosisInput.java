package com.ruoyi.train.agent.contract;

import java.util.List;
import java.util.Map;

public record DiagnosisInput(int schemaVersion, long jobId, String status, Integer exitCode,
        List<String> logEvidence, Map<String, Double> metrics, List<String> alerts)
{
    public DiagnosisInput
    {
        logEvidence = List.copyOf(logEvidence);
        metrics = Map.copyOf(metrics);
        alerts = List.copyOf(alerts);
    }
}
