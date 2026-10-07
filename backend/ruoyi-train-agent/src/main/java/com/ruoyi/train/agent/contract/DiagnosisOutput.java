package com.ruoyi.train.agent.contract;

import java.util.List;

public record DiagnosisOutput(int schemaVersion, String category, double confidence,
        List<String> evidence, List<String> probableCauses, List<String> recommendations)
{
    public DiagnosisOutput
    {
        evidence = List.copyOf(evidence);
        probableCauses = List.copyOf(probableCauses);
        recommendations = List.copyOf(recommendations);
    }
}
