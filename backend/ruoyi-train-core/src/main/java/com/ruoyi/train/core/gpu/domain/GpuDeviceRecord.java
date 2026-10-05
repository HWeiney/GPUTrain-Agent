package com.ruoyi.train.core.gpu.domain;

import java.time.Instant;

public record GpuDeviceRecord(Long deviceId, String gpuUuid, String hostSignature,
        String name, String identityState, boolean schedulable,
        Instant createdAt, Instant updatedAt)
{
}
