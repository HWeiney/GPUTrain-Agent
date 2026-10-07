package com.ruoyi.train.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;

class QwenCompatibleChatClientTest
{
    @Test
    void springCanCreateTheClientUsingItsConfiguredConstructor()
    {
        try (var context = new AnnotationConfigApplicationContext())
        {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(QwenCompatibleChatClient.class);

            assertDoesNotThrow(context::refresh);
            assertEquals(QwenCompatibleChatClient.class, context.getBean(StructuredAgentModel.class).getClass());
        }
    }

    @Test
    void missingApiKeyFailsClosedWithoutSendingARequest()
    {
        var client = new QwenCompatibleChatClient(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://127.0.0.1:1/chat/completions"), "", "qwen-plus", Duration.ofSeconds(1));

        AgentModelException error = assertThrows(AgentModelException.class,
                () -> client.complete(AgentRole.PLANNER, "system", new ObjectMapper().createObjectNode()));

        assertEquals("QWEN_API_KEY_NOT_CONFIGURED", error.getMessage());
        assertFalse(error.getMessage().contains("Bearer"));
    }
}
