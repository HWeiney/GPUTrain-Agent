package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RetryBackoffTest
{
    @Test void growsExponentiallyCapsAtThirtySecondsAndResets()
    {
        RetryBackoff backoff = new RetryBackoff(() -> 0);
        assertEquals(500, backoff.nextDelayMillis());
        assertEquals(1000, backoff.nextDelayMillis());
        assertEquals(2000, backoff.nextDelayMillis());
        for (int i = 0; i < 100; i++) assertTrue(backoff.nextDelayMillis() <= 30000);
        assertEquals(15000, backoff.nextDelayMillis());
        backoff.reset(); assertEquals(500, backoff.nextDelayMillis());
    }
    @Test void jitterChangesDelayWithinTheSameBound()
    {
        assertEquals(750, new RetryBackoff(() -> 0.5).nextDelayMillis());
        assertEquals(999, new RetryBackoff(() -> 0.999).nextDelayMillis());
    }
}
