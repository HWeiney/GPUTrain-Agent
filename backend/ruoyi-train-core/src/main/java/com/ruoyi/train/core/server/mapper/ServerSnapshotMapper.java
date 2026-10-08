package com.ruoyi.train.core.server.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerSnapshotRecord;

public interface ServerSnapshotMapper
{
    int insert(ServerSnapshotRecord snapshot);

    ServerSnapshotRecord findLatest(@Param("serverId") Long serverId,
            @Param("accountId") Long accountId);
}
