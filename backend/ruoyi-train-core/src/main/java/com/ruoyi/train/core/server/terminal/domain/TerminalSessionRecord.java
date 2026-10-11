package com.ruoyi.train.core.server.terminal.domain;

import java.time.Instant;

/**
 * 终端会话审计行。主键 session_uuid 由应用侧生成（record 不可变，
 * 规避 useGeneratedKeys 回填），状态三态：ACTIVE → CLOSED / ERROR，
 * 与 train_mcp_audit 的三态审计模型一致。
 */
public record TerminalSessionRecord(
    String sessionUuid, Long actorUserId, Long serverId, Long accountId,
    String status, String closeReason, String errorCode,
    Instant openedAt, Instant closedAt)
{
}
