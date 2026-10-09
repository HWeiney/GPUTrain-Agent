package com.ruoyi.train.core.server.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;

public interface ServerAccountMapper
{
    int insert(ServerAccountRecord account);

    ServerAccountRecord findById(@Param("accountId") Long accountId);

    List<ServerAccountRecord> listForOwner(@Param("serverId") Long serverId,
            @Param("ownerUserId") Long ownerUserId);

    /** Call only after the service authorizes this account for the current actor. */
    ServerAccountRecord findCredentialsByAuthorizedAccountId(
            @Param("authorizedAccountId") Long authorizedAccountId);

    /** Internal scheduler metadata only; enabled servers/accounts, without credential columns. */
    List<ServerAccountRecord> listEnabledForProbe(@Param("afterAccountId") Long afterAccountId,
            @Param("limit") int limit);

    int updateCredentials(@Param("authorizedAccountId") Long authorizedAccountId,
            @Param("credentialCiphertext") String credentialCiphertext,
            @Param("passphraseCiphertext") String passphraseCiphertext,
            @Param("updatedAt") Instant updatedAt);

    int updateProbeOutcome(@Param("accountId") Long accountId, @Param("status") String status,
            @Param("errorCode") String errorCode, @Param("sampledAt") Instant sampledAt,
            @Param("successful") boolean successful);
}
