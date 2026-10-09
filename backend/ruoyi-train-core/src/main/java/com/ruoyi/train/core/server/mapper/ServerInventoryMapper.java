package com.ruoyi.train.core.server.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerRecord;

/** Inventory administration queries; no statement reads or accepts account secrets. */
public interface ServerInventoryMapper
{
    ServerRecord findByAddress(@Param("host") String host,@Param("port") int port);
    List<ServerRecord> findByFingerprint(@Param("fingerprint") String fingerprint);
    boolean userExists(@Param("userId") Long userId);
    int updateMetadata(@Param("serverId") Long serverId,@Param("name") String name,
        @Param("fingerprint") String fingerprint,@Param("enabled") boolean enabled,@Param("updatedAt") Instant updatedAt);
    int countOnline(@Param("ownerUserId") long ownerUserId,@Param("isAdmin") boolean isAdmin,@Param("freshAfter") Instant freshAfter,@Param("now") Instant now);
}
