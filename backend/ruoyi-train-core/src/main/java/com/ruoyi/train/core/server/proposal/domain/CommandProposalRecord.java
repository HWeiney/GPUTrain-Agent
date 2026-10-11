package com.ruoyi.train.core.server.proposal.domain;

import java.time.Instant;

/**
 * 命令提案：动作 + 参数 + 渲染后的命令，四态状态机
 * PENDING →（批准 CAS）EXECUTED（exit_code NULL → attachResult 写入结果）
 *        →（拒绝 CAS）REJECTED /（执行层失败）FAILED + error_code。
 * exitCode≠0 是远端命令的合法可观测结果（EXECUTED 承载）。
 * rationale 仅 source=AGENT 时非空：模型选择该动作的依据（展示与审计）。
 */
public record CommandProposalRecord(
    String proposalId, Long actorUserId, Long serverId, Long accountId,
    String source, String rationale, String action, String argsJson, String renderedCommand,
    String status, Integer exitCode, String stdout, String stderr, String errorCode,
    Instant createdAt, Instant decidedAt, Instant executedAt)
{
}
