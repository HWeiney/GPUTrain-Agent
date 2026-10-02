package com.ruoyi;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LocalApplicationConfigurationTest
{
    private static final List<String> REQUIRED_DRUID_PROPERTIES = List.of(
            "initialSize",
            "minIdle",
            "maxActive",
            "maxWait",
            "connectTimeout",
            "socketTimeout",
            "timeBetweenEvictionRunsMillis",
            "minEvictableIdleTimeMillis",
            "maxEvictableIdleTimeMillis",
            "validationQuery",
            "testWhileIdle",
            "testOnBorrow",
            "testOnReturn");

    @Test
    void shouldProvideAllDruidPropertiesRequiredByTheLocalProfile() throws IOException
    {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application-local.yml"))
        {
            assertNotNull(input, "application-local.yml should exist on the classpath");
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> druid = nestedMap(root, "spring", "datasource", "druid");

            for (String property : REQUIRED_DRUID_PROPERTIES)
            {
                assertNotNull(druid.get(property), "Missing required Druid property: " + property);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> root, String... keys)
    {
        Map<String, Object> current = root;
        for (String key : keys)
        {
            current = (Map<String, Object>) current.get(key);
            assertNotNull(current, "Missing configuration section: " + key);
        }
        return current;
    }
}
