package com.ruoyi.train.agent.contract;

/**
 * 发给模型的记忆轮次最小形状：意图 → 动作 → 结果（JOIN 提案表实时获得）。
 * 无 memoryId/createdAt/args/账户信息——只保留模型理解上下文所需字段，秘密边界不变。
 */
public record MemoryTurn(String intentText, String action, String status, Integer exitCode, String outputExcerpt)
{
}
