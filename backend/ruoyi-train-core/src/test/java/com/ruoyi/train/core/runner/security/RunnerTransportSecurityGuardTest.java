package com.ruoyi.train.core.runner.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

class RunnerTransportSecurityGuardTest
{
    @Test
    void httpsAlwaysPasses()
    {
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(new RunnerSecurityProperties());
        assertDoesNotThrow(() -> guard.assertAllowed(true, "203.0.113.10"));
    }

    @Test
    void explicitlyEnabledLoopbackHttpPasses()
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setRequireHttps(false);
        properties.setAllowLoopbackHttp(true);
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(properties);
        assertDoesNotThrow(() -> guard.assertAllowed(false, "127.0.0.1"));
        assertDoesNotThrow(() -> guard.assertAllowed(false, "::1"));
    }

    @Test
    void nonLoopbackHttpIsRejectedEvenWhenLocalHttpIsEnabled()
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setRequireHttps(false);
        properties.setAllowLoopbackHttp(true);
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(properties);
        RunnerProtocolException exception = assertThrows(RunnerProtocolException.class,
                () -> guard.assertAllowed(false, "192.0.2.4"));
        assertEquals(RunnerErrorCode.RUNNER_HTTPS_REQUIRED, exception.code());
    }

    @Test
    void localHttpMustBeExplicitlyEnabled()
    {
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(new RunnerSecurityProperties());
        assertThrows(RunnerProtocolException.class, () -> guard.assertAllowed(false, "127.0.0.1"));
    }
}
