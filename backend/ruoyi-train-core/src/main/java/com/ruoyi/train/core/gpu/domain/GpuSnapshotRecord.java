package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

public record GpuSnapshotRecord(Long snapshotId, Long runnerId, String gpuUuid,
        int visibleIndex, long totalMemoryMiB, long usedMemoryMiB,
        int utilizationPercent, String migMode, Instant sampledAt,
        String computeProcessesJson, Instant createdAt)
{
}
