package com.ruoyi.train.core.observability;

import java.time.Instant;

public record JobAlert(long alertId, String ruleCode, String severity, String message, Instant createdAt) { }
