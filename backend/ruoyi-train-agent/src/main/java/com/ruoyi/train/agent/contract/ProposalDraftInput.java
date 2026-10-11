package com.ruoyi.train.agent.contract;

import java.util.List;

/**
 * 提案起草输入：动作目录（无秘密）+ 用户意图 + 会话长记忆（最近 N 轮，时间正序）；
 * 服务器/账户/凭证不进模型输入。
 */
public record ProposalDraftInput(int schemaVersion, String intentText, List<CatalogAction> actions,
        List<MemoryTurn> history)
{
}
