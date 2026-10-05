package com.ruoyi.train.core.gpu.service;

import java.util.List;
import com.ruoyi.train.core.gpu.domain.GpuDeviceDecision;

/**
 * The validated, persistable view of one GPU discovery report.
 *
 * @param runnerId numeric Runner id
 * @param hostSignature host identity digest
 * @param observable whether GPUs were observable (only an OK report yields decisions)
 * @param decisions per-device catalog decisions
 */
public record ValidatedGpuReport(long runnerId, String hostSignature,
        boolean observable, List<GpuDeviceDecision> decisions)
{
    public ValidatedGpuReport
    {
        decisions = List.copyOf(decisions);
    }
}
