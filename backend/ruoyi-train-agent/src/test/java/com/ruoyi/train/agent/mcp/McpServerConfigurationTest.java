package com.ruoyi.train.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.observability.JobObservabilityService;

class McpServerConfigurationTest
{
    @Test
    void registersExactlyTheReadOnlyAllowlist()
    {
        var configuration = new McpServerConfiguration();
        var transport = configuration.mcpTransport();
        var queries = new McpQueryService(mock(JobService.class), mock(JobObservabilityService.class));
        var server = configuration.mcpServer(transport, queries, mock(McpAuditService.class), new ObjectMapper());
        try
        {
            Set<String> names = server.listTools().stream().map(tool -> tool.name()).collect(Collectors.toSet());
            assertEquals(Set.of("list_my_experiments", "get_my_experiment", "get_experiment_logs",
                    "get_experiment_metrics", "get_experiment_alerts", "get_experiment_resources"), names);
        }
        finally { server.close(); }
    }
}
