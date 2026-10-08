package com.ruoyi.train.core.server.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerRecord;

public interface ServerMapper
{
    int insert(ServerRecord server);

    ServerRecord findById(@Param("serverId") Long serverId);

    List<ServerRecord> listForOwner(@Param("ownerUserId") Long ownerUserId,
            @Param("isAdmin") boolean isAdmin);

    int setEnabled(@Param("serverId") Long serverId, @Param("enabled") boolean enabled,
            @Param("updatedAt") Instant updatedAt);
}
