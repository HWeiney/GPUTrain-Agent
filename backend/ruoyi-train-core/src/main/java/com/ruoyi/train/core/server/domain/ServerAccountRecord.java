package com.ruoyi.train.core.server.domain;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ServerAccountRecord(
    Long accountId, Long serverId, Long ownerUserId,
    String linuxUsername, String authType, @JsonIgnore String credentialCiphertext,
    @JsonIgnore String passphraseCiphertext, boolean hasCredential, String allowedRoot,
    String profileJson, boolean enabled, String status,
    String errorCode, Instant lastProbeAt, Instant lastSuccessAt,
    Instant createdAt, Instant updatedAt)
{
    @Override
    public String toString()
    {
        return "ServerAccountRecord[accountId=" + accountId + ", serverId=" + serverId + "]";
    }
}
