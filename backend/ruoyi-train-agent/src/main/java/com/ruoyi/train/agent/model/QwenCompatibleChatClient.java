package com.ruoyi.train.agent.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Qwen OpenAI-compatible adapter; deliberately does not send tool definitions. */
@Component
public class QwenCompatibleChatClient implements StructuredAgentModel
{
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    @Autowired
    public QwenCompatibleChatClient(ObjectMapper mapper,
            @Value("${gputrain.agent.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${gputrain.agent.qwen.api-key:${QWEN_KEY:${DASHSCOPE_API_KEY:}}}") String apiKey,
            @Value("${gputrain.agent.qwen.model:qwen3.8-27b}") String model,
            @Value("${gputrain.agent.qwen.timeout-seconds:45}") long timeoutSeconds)
    {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), mapper,
                URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"), apiKey, model,
                Duration.ofSeconds(timeoutSeconds));
    }

    QwenCompatibleChatClient(HttpClient http, ObjectMapper mapper, URI endpoint,
            String apiKey, String model, Duration timeout)
    {
        this.http = http; this.mapper = mapper; this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.trim(); this.model = model; this.timeout = timeout;
    }

    @Override
    public JsonNode complete(AgentRole role, String systemPrompt, JsonNode input)
    {
        if (apiKey.isBlank()) throw new AgentModelException("QWEN_API_KEY_NOT_CONFIGURED");
        try
        {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", 1200,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", input.toString()))));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AgentModelException("QWEN_HTTP_STATUS_" + response.statusCode());
            JsonNode content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank())
                throw new AgentModelException("QWEN_RESPONSE_CONTENT_MISSING");
            JsonNode output = mapper.readTree(content.asText());
            if (output == null || !output.isObject()) throw new AgentModelException("QWEN_OUTPUT_NOT_JSON_OBJECT");
            return output;
        }
        catch (AgentModelException failure) { throw failure; }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new AgentModelException("QWEN_REQUEST_INTERRUPTED");
        }
        catch (IOException | RuntimeException failure)
        { throw new AgentModelException("QWEN_RESPONSE_INVALID_OR_UNAVAILABLE"); }
    }
}
