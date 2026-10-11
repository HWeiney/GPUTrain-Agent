package com.ruoyi.train.core.server.agentmemory.domain;

import java.time.Instant;

/**
 * 记忆轮次视图：JOIN train_command_proposal 的产物——
 * status/exitCode/outputExcerpt（stdout 前 600 字符）随提案状态实时变化，无需回写记忆行。
 */
public record MemoryTurnView(String memoryId, String intentText, String action,
        String status, Integer exitCode, String outputExcerpt, Instant createdAt)
{ }
