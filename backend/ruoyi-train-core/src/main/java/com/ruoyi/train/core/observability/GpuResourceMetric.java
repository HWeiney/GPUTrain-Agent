package com.ruoyi.train.core.observability;

import java.time.Instant;

public record GpuResourceMetric(String gpuUuid, int utilizationPercent, long totalMemoryMiB,
        long usedMemoryMiB, Instant sampledAt) { }
