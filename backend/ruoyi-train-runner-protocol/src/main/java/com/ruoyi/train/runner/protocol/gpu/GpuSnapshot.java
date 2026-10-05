package com.ruoyi.train.runner.protocol.gpu;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of one NVIDIA GPU.
 *
 * @param uuid NVIDIA GPU UUID (persistent identity)
 * @param name product name
 * @param visibleIndex enumeration ordinal within this sample, display only
 * @param totalMemoryMiB total frame buffer
 * @param usedMemoryMiB used frame buffer
 * @param utilizationPercent GPU utilization 0..100
 * @param migMode current MIG mode text (ENABLED / DISABLED / N/A)
 * @param sampledAt sample time
 * @param computeProcesses compute processes observed on this GPU
 */
public record GpuSnapshot(String uuid, String name, int visibleIndex,
        long totalMemoryMiB, long usedMemoryMiB, int utilizationPercent,
        String migMode, Instant sampledAt, List<GpuProcessInfo> computeProcesses)
{
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^(GPU|MIG)-[A-Za-z0-9-]+$");

    public GpuSnapshot
    {
        if (uuid == null || uuid.isBlank() || !UUID_PATTERN.matcher(uuid).matches())
            throw new IllegalArgumentException("GPU UUID must match NVIDIA UUID form");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (totalMemoryMiB < 0) throw new IllegalArgumentException("totalMemoryMiB must not be negative");
        if (usedMemoryMiB < 0 || usedMemoryMiB > totalMemoryMiB)
            throw new IllegalArgumentException("usedMemoryMiB must be between 0 and totalMemoryMiB");
        if (utilizationPercent < 0 || utilizationPercent > 100)
            throw new IllegalArgumentException("utilizationPercent must be within 0..100");
        if (migMode == null || migMode.isBlank())
            throw new IllegalArgumentException("migMode must not be blank");
        Objects.requireNonNull(sampledAt, "sampledAt");
        computeProcesses = List.copyOf(computeProcesses);
    }
}
