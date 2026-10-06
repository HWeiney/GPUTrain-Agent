package com.ruoyi.train.core.gpu.service;

import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;

/**
 * A device considered for allocation, combining catalog and freshness facts.
 *
 * @param gpuUuid persistent GPU UUID
 * @param hostSignature host identity digest
 * @param totalMemoryMiB total frame buffer
 * @param schedulable catalog schedulable flag
 * @param fresh whether the latest snapshot is within the freshness window
 * @param identityState derived device identity state
 */
public record GpuAllocationCandidate(String gpuUuid, String hostSignature, long totalMemoryMiB,
        long usedMemoryMiB,
        boolean schedulable, boolean fresh, DeviceIdentityState identityState, long runnerId)
{
    public GpuAllocationCandidate(String gpuUuid, String hostSignature, long totalMemoryMiB,
            boolean schedulable, boolean fresh, DeviceIdentityState identityState, long runnerId)
    { this(gpuUuid, hostSignature, totalMemoryMiB, 0, schedulable, fresh, identityState, runnerId); }
}
