package com.ruoyi.train.core.server.domain;

import java.time.Instant;

public record ServerRecord(
    Long serverId, String name, String host,
    int port, String hostKeySha256, boolean enabled,
    Instant createdAt, Instant updatedAt)
{
}
