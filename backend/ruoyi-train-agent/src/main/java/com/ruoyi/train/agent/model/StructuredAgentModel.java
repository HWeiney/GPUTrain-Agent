package com.ruoyi.train.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public interface StructuredAgentModel
{
    JsonNode complete(AgentRole role, String systemPrompt, JsonNode input);
}
