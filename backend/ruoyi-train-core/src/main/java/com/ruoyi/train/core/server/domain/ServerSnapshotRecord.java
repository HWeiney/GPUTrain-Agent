package com.ruoyi.train.core.server.domain;

import java.time.Instant;

public record ServerSnapshotRecord(
    Long snapshotId, Long serverId, Long accountId,
    Instant sampledAt, String status, String errorCode,
    String capabilitiesJson, String factsJson, Instant createdAt)
{
}
