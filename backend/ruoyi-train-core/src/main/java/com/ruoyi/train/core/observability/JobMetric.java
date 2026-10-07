package com.ruoyi.train.core.observability;

import java.time.Instant;

public record JobMetric(long metricId, String name, double value, Long step, Instant sampledAt) { }
