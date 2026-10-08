package com.ruoyi.train.core.server.domain;

import java.time.Instant;

public record ServerGpuSnapshotRecord(
    Long snapshotId, Long serverId, Long accountId,
    String gpuUuid, int visibleIndex, long totalMemoryMiB,
    long usedMemoryMiB, int utilizationPercent, String migMode,
    String driverVersion, String computeProcessesJson, Instant sampledAt,
    Instant createdAt)
{
}
