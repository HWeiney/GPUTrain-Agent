package com.ruoyi.train.core.runner.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RunnerSecurityPropertiesTest
{
    @Test
    void decodesExactly32ByteMasterKeyAndRejectsMissingSecrets()
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        byte[] key = new byte[32];
        properties.setMasterKey(Base64.getEncoder().encodeToString(key));
        properties.setEnrollmentPepper("configured-pepper");
        assertArrayEquals(key, properties.getMasterKeyBytes());

        RunnerSecurityProperties missing = new RunnerSecurityProperties();
        assertThrows(IllegalStateException.class, missing::getMasterKeyBytes);
        assertThrows(IllegalStateException.class, missing::getEnrollmentPepper);
        org.junit.jupiter.api.Assertions.assertFalse(missing.isAllowLoopbackHttp());
    }

    @Test
    void rejectsWrongLengthMasterKey()
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[31]));
        assertThrows(IllegalStateException.class, properties::getMasterKeyBytes);
    }

    @Test
    void runnerBodyLimitDefaultsTo64KiBAndBindsFromConfiguration()
    {
        assertEquals(65_536, new RunnerSecurityProperties().getMaxRequestBodyBytes());
        var source = new MapConfigurationPropertySource(Map.of(
                "gputrain.runner-security.max-request-body-bytes", "4096"));
        RunnerSecurityProperties bound = new Binder(source).bind("gputrain.runner-security",
                RunnerSecurityProperties.class).get();
        assertEquals(4096, bound.getMaxRequestBodyBytes());
        assertThrows(IllegalArgumentException.class, () -> bound.setMaxRequestBodyBytes(0));
    }
}
