package com.ruoyi.train.core.observability;

import java.time.Instant;

public record JobLogEvent(long eventId, String stream, long offset, long nextOffset, String text, Instant createdAt) { }
