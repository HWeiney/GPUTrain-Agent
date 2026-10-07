package com.ruoyi.train.agent.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class McpAuditServiceTest
{
    @Test
    void persistsOnlyAuditMetadata()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpAuditService audit = new McpAuditService(jdbc);

        audit.record(42, "get_my_experiment", 7L, "DENIED", "NOT_FOUND");

        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
