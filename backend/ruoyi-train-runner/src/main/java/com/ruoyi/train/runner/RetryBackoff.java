package com.ruoyi.train.runner;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Equal-jitter exponential backoff: 0.5–1 seconds initially, capped at 30 seconds. */
final class RetryBackoff
{
    private final DoubleSupplier random;
    private long ceilingMillis = 1000;

    RetryBackoff() { this(() -> ThreadLocalRandom.current().nextDouble()); }
    RetryBackoff(DoubleSupplier random) { this.random = random; }

    long nextDelayMillis()
    {
        long delay = ceilingMillis / 2 + (long) (random.getAsDouble() * (ceilingMillis / 2));
        ceilingMillis = Math.min(30000, ceilingMillis * 2);
        return delay;
    }

    void reset() { ceilingMillis = 1000; }
}
