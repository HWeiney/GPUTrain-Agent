package com.ruoyi.train.runner.protocol.gpu;

import java.util.Objects;

/**
 * A single compute process observed on a GPU.
 *
 * @param pid host PID
 * @param processName process image name (may be "[Not Found]" when inaccessible)
 * @param usedMemoryMiB memory attributed to the process
 * @param gpuInstanceId MIG instance id when present, otherwise null
 */
public record GpuProcessInfo(long pid, String processName, long usedMemoryMiB,
        String gpuInstanceId)
{
    public GpuProcessInfo
    {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        Objects.requireNonNull(processName, "processName");
        if (usedMemoryMiB < 0) throw new IllegalArgumentException("usedMemoryMiB must not be negative");
    }
}
