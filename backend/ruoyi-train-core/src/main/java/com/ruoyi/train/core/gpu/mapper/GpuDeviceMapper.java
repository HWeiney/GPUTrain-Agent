package com.ruoyi.train.core.gpu.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.gpu.domain.GpuDeviceRecord;
import com.ruoyi.train.core.gpu.domain.GpuDeviceView;

public interface GpuDeviceMapper
{
    int upsert(GpuDeviceRecord device);

    GpuDeviceRecord findByUuid(@Param("gpuUuid") String gpuUuid);

    List<GpuDeviceRecord> listByHost(@Param("hostSignature") String hostSignature);

    /** Lists devices with their latest snapshot and active lease / external occupancy. */
    List<GpuDeviceView> listDevices();

    /** Marks devices on a host absent from the current report as unobservable (fail closed). */
    int markAbsentUnobservable(@Param("hostSignature") String hostSignature,
            @Param("visibleUuids") List<String> visibleUuids,
            @Param("updatedAt") Instant updatedAt);
}
