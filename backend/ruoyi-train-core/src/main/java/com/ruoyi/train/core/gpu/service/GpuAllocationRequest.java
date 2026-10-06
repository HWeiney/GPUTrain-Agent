package com.ruoyi.train.core.gpu.service;

import java.util.Set;

/**
 * Constraints for a GPU allocation request.
 *
 * @param gpuCount number of GPUs required
 * @param minMemoryMiB minimum total memory per GPU
 * @param allowedUuids when non-empty, only these UUIDs may be chosen
 * @param deniedUuids these UUIDs may never be chosen
 * @param requireSameHost whether all GPUs must be on the same host
 */
public record GpuAllocationRequest(long ownerUserId, int gpuCount, long minMemoryMiB,
        Set<String> allowedUuids, Set<String> deniedUuids, boolean requireSameHost,
        long minFreeMemoryMiB, boolean allowExternalOccupancy)
{
    public GpuAllocationRequest(long ownerUserId, int gpuCount, long minMemoryMiB,
            Set<String> allowedUuids, Set<String> deniedUuids, boolean requireSameHost)
    { this(ownerUserId, gpuCount, minMemoryMiB, allowedUuids, deniedUuids, requireSameHost, 0, false); }
    public GpuAllocationRequest
    {
        if (ownerUserId <= 0) throw new IllegalArgumentException("ownerUserId must be positive");
        if (gpuCount <= 0) throw new IllegalArgumentException("gpuCount must be positive");
        if (minMemoryMiB < 0) throw new IllegalArgumentException("minMemoryMiB must not be negative");
        if (minFreeMemoryMiB < 0) throw new IllegalArgumentException("minFreeMemoryMiB must not be negative");
        allowedUuids = allowedUuids == null ? Set.of() : Set.copyOf(allowedUuids);
        deniedUuids = deniedUuids == null ? Set.of() : Set.copyOf(deniedUuids);
    }
}
