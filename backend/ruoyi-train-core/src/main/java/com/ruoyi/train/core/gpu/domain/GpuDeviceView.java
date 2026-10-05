package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

/**
 * Read model for the device list: combines the canonical device record with its latest
 * observed snapshot. It is a view, never an authority for "free"; scheduling decisions
 * always go through the catalog and lease service.
 */
public record GpuDeviceView(String gpuUuid, String name, String hostSignature,
        String identityState, boolean schedulable, Instant sampledAt,
        long totalMemoryMiB, long usedMemoryMiB, int utilizationPercent,
        String migMode, boolean hasActiveLease, int externalProcessCount)
{
}
