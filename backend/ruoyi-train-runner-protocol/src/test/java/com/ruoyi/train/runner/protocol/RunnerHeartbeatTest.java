package com.ruoyi.train.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunnerHeartbeatTest
{
    @Test
    void preservesRunnerIdentityAndTimestamp()
    {
        Instant sentAt = Instant.parse("2026-10-02T08:00:00Z");

        RunnerHeartbeat heartbeat = new RunnerHeartbeat("runner-hanwei", "hanwei", sentAt);

        assertEquals("runner-hanwei", heartbeat.runnerId());
        assertEquals("hanwei", heartbeat.linuxUsername());
        assertEquals(sentAt, heartbeat.sentAt());
    }
}
