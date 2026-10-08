package com.ruoyi.train.core.server.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerGpuSnapshotRecord;

public interface ServerGpuSnapshotMapper
{
    int insert(ServerGpuSnapshotRecord snapshot);

    ServerGpuSnapshotRecord findLatest(@Param("serverId") Long serverId,
            @Param("accountId") Long accountId, @Param("gpuUuid") String gpuUuid);
}
