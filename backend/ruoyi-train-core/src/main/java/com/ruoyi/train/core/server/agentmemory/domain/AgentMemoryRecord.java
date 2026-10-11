package com.ruoyi.train.core.server.agentmemory.domain;

import java.time.Instant;

/** Agent 会话长记忆轮次：意图 → 动作 → 依据 → 提案链接；append-only，落库后不更新。 */
public record AgentMemoryRecord(String memoryId, Long actorUserId, Long serverId,
        String intentText, String action, String argsJson, String rationale,
        String proposalId, Instant createdAt)
{ }
