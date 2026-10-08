package com.ruoyi.train.core.server.domain;

import java.time.Instant;

public record ServerGpuDeviceRecord(
    Long deviceId, Long serverId, String gpuUuid,
    String name, String identityState, boolean schedulable,
    Instant createdAt, Instant updatedAt)
{
}
