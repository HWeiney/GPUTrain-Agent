package com.ruoyi.train.runner.protocol.gpu;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of one GPU discovery cycle on a Runner.
 *
 * @param runnerId authenticated Runner id
 * @param hostName host name
 * @param driverVersion NVIDIA driver version
 * @param nvidiaSmiVersion nvidia-smi version
 * @param hostSignature host identity digest
 * @param discoveredAt discovery time
 * @param status discovery status (only OK implies observable GPUs)
 * @param gpus observed GPUs
 * @param errorMessage fixed safe error text, never raw command output
 */
public record GpuDiscoveryReport(String runnerId, String hostName,
        String driverVersion, String nvidiaSmiVersion, String hostSignature,
        Instant discoveredAt, GpuDiscoveryStatus status, List<GpuSnapshot> gpus,
        String errorMessage)
{
    public static final int MAX_GPUS = 64;

    public GpuDiscoveryReport
    {
        if (runnerId == null || runnerId.isBlank())
            throw new IllegalArgumentException("runnerId must not be blank");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        gpus = List.copyOf(gpus);
        if (gpus.size() > MAX_GPUS)
            throw new IllegalArgumentException("GPU count exceeds discovery limit");
        if (status == GpuDiscoveryStatus.OK && gpus.isEmpty())
            throw new IllegalArgumentException("OK report must contain GPUs");
    }
}
