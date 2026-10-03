package com.ruoyi.train.core.runner.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnrollmentTokenDigesterTest
{
    @Test
    void tokenDigestUsesServerPepperAndConstantTimeComparison()
    {
        EnrollmentTokenDigester digester = new EnrollmentTokenDigester(
                "pepper-value".getBytes(StandardCharsets.UTF_8));
        String digest = digester.digest("one-time-token");
        assertTrue(digester.matches("one-time-token", digest));
        assertFalse(digester.matches("wrong-token", digest));
    }

    @Test
    void differentPeppersProduceDifferentDigests()
    {
        String token = "one-time-token";
        String digest = new EnrollmentTokenDigester("first".getBytes(StandardCharsets.UTF_8)).digest(token);
        assertFalse(new EnrollmentTokenDigester("second".getBytes(StandardCharsets.UTF_8)).matches(token, digest));
    }
}
