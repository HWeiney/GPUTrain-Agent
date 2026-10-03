package com.ruoyi.train.core.runner.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Base64;
import org.junit.jupiter.api.Test;

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
}
