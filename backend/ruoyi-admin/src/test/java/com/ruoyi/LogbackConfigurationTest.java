package com.ruoyi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LogbackConfigurationTest
{
    @Test
    void shouldUseConfigurablePortableLogDirectory() throws IOException
    {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("logback.xml"))
        {
            assertTrue(input != null, "logback.xml should exist on the classpath");
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(configuration.contains("${LOG_PATH:-./logs}"));
            assertFalse(configuration.contains("/home/ruoyi/logs"));
        }
    }
}
