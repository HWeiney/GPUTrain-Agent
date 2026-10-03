package com.ruoyi.train.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RunnerRequestSignerTest
{
    @Test
    void canonicalRequestAndSignatureAreDeterministic()
    {
        String canonical = RunnerRequestSigner.canonical("runner-1", 1_780_000_000L, "nonce-1",
            "post", "/runner-api/v1/heartbeat", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals("runner-1\n1780000000\nnonce-1\nPOST\n/runner-api/v1/heartbeat\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", canonical);
        assertEquals("XxcJvlUb_Wfx2jLNRPnJvZLJWmVHzkB-mlMmxPtQ0-E",
            RunnerRequestSigner.sign("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), canonical));
    }
}
