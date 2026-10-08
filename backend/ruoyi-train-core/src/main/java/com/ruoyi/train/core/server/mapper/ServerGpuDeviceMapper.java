package com.ruoyi.train.core.server.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerGpuDeviceRecord;

public interface ServerGpuDeviceMapper
{
    int upsert(ServerGpuDeviceRecord device);

    ServerGpuDeviceRecord findByUuid(@Param("serverId") Long serverId,
            @Param("gpuUuid") String gpuUuid);

    List<ServerGpuDeviceRecord> listByServer(@Param("serverId") Long serverId);
}
