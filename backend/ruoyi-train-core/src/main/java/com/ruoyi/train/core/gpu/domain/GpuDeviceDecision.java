package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

/**
 * A validated per-device decision derived from a report, ready to persist in the catalog.
 *
 * @param gpuUuid persistent NVIDIA GPU UUID
 * @param hostSignature host identity digest
 * @param name product name
 * @param visibleIndex index as seen by the Runner (display only)
 * @param identityState derived safety state
 * @param schedulable whether the control plane may consider it for a new lease
 * @param totalMemoryMiB total frame buffer
 * @param usedMemoryMiB used frame buffer
 * @param utilizationPercent utilization 0..100
 * @param migMode raw MIG mode text
 * @param sampledAt sample time
 * @param externalPids unconfirmed process PIDs on the device
 */
public record GpuDeviceDecision(String gpuUuid, String hostSignature, String name,
        int visibleIndex, DeviceIdentityState identityState, boolean schedulable,
        long totalMemoryMiB, long usedMemoryMiB, int utilizationPercent,
        String migMode, Instant sampledAt, java.util.List<Long> externalPids)
{
}
