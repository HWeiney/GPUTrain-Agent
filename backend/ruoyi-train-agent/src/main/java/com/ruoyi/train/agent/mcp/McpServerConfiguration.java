package com.ruoyi.train.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.SecurityUtils;

@Configuration
public class McpServerConfiguration
{
    private static final String USER_CONTEXT_KEY = "authenticatedUserId";
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport()
    {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(USER_CONTEXT_KEY, SecurityUtils.getUserId())))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport)
    {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport);
        registration.setName("gputrainMcpServlet");
        registration.setUrlMappings(java.util.Set.of("/mcp"));
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public io.modelcontextprotocol.server.McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transport, McpQueryService queries,
            McpAuditService audit, ObjectMapper objectMapper)
    {
        var server = McpServer.sync(transport)
                .serverInfo("gputrain-agent", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .build();
        addTool(server, tool("list_my_experiments", "List the authenticated user's training experiments", Map.of()),
                (exchange, request) -> invoke(exchange, request, "list_my_experiments", null,
                        userId -> queries.listMyExperiments(userId), objectMapper, audit));
        addTool(server, tool("get_my_experiment", "Get a safe summary of one owned experiment", jobIdSchema()),
                (exchange, request) -> invokeJob(exchange, request, "get_my_experiment", queries::getMyExperiment, objectMapper, audit));
        addTool(server, tool("get_experiment_logs", "Read recent logs for one owned experiment", logSchema()),
                (exchange, request) -> invokeJob(exchange, request, "get_experiment_logs",
                        (userId, jobId) -> queries.logs(userId, jobId, number(request, "afterEventId", 0),
                                (int) number(request, "limit", 50)), objectMapper, audit));
        addTool(server, tool("get_experiment_metrics", "Read recorded metrics for one owned experiment", jobIdSchema()),
                (exchange, request) -> invokeJob(exchange, request, "get_experiment_metrics", queries::metrics, objectMapper, audit));
        addTool(server, tool("get_experiment_alerts", "Read recorded alerts for one owned experiment", jobIdSchema()),
                (exchange, request) -> invokeJob(exchange, request, "get_experiment_alerts", queries::alerts, objectMapper, audit));
        addTool(server, tool("get_experiment_resources", "Read GPU resource history for one owned experiment", jobIdSchema()),
                (exchange, request) -> invokeJob(exchange, request, "get_experiment_resources", queries::resources, objectMapper, audit));
        return server;
    }

    private static void addTool(io.modelcontextprotocol.server.McpSyncServer server, Tool tool,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler)
    {
        server.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler(handler).build());
    }

    private static CallToolResult invokeJob(McpSyncServerExchange exchange, CallToolRequest request, String tool,
            JobQuery query, ObjectMapper json, McpAuditService audit)
    {
        Long auditJobId = null;
        try
        {
            long parsedJobId = number(request, "jobId", -1);
            if (parsedJobId <= 0) throw new IllegalArgumentException("Invalid jobId");
            auditJobId = parsedJobId;
            Long targetJobId = auditJobId;
            return invoke(exchange, request, tool, targetJobId, userId -> query.run(userId, parsedJobId), json, audit);
        }
        catch (RuntimeException invalid)
        {
            long userId = authenticatedUser(exchange.transportContext());
            audit.record(userId, tool, auditJobId, "DENIED", "INVALID_ARGUMENT");
            return error("Invalid tool arguments");
        }
    }

    private static CallToolResult invoke(McpSyncServerExchange exchange, CallToolRequest request, String tool,
            Long jobId, UserQuery query, ObjectMapper json, McpAuditService audit)
    {
        long userId = authenticatedUser(exchange.transportContext());
        try
        {
            Object result = query.run(userId);
            audit.record(userId, tool, jobId, "SUCCESS", null);
            return CallToolResult.builder().content(List.of(new McpSchema.TextContent(json.writeValueAsString(result)))).build();
        }
        catch (IllegalArgumentException denied)
        {
            audit.record(userId, tool, jobId, "DENIED", "NOT_FOUND");
            return error("Experiment not found");
        }
        catch (Exception failure)
        {
            audit.record(userId, tool, jobId, "ERROR", "QUERY_FAILED");
            return error("Experiment query failed");
        }
    }

    private static long authenticatedUser(McpTransportContext context)
    {
        Object userId = context.get(USER_CONTEXT_KEY);
        if (!(userId instanceof Number number) || number.longValue() <= 0)
            throw new SecurityException("Authenticated user context is missing");
        return number.longValue();
    }

    private static long number(CallToolRequest request, String name, long defaultValue)
    {
        Object value = request.arguments() == null ? null : request.arguments().get(name);
        if (value == null) return defaultValue;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid numeric argument");
        return number.longValue();
    }

    private static Tool tool(String name, String description, Map<String, Object> properties)
    {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        List<String> required = properties.containsKey("jobId") ? List.of("jobId") : List.of();
        if (!required.isEmpty()) schema.put("required", required);
        return Tool.builder(name, schema).description(description).build();
    }

    private static Map<String, Object> jobIdSchema()
    { return Map.of("jobId", Map.of("type", "integer", "minimum", 1)); }

    private static Map<String, Object> logSchema()
    {
        return Map.of("jobId", Map.of("type", "integer", "minimum", 1),
                "afterEventId", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100));
    }

    private static CallToolResult error(String message)
    { return CallToolResult.builder().content(List.of(new McpSchema.TextContent(message))).isError(true).build(); }

    @FunctionalInterface private interface UserQuery { Object run(long userId) throws Exception; }
    @FunctionalInterface private interface JobQuery { Object run(long userId, long jobId); }
}
