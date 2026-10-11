package com.ruoyi.train.core.server.terminal.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.terminal.domain.TerminalSessionRecord;

/**
 * 终端会话审计：打开尝试即落行（ACTIVE），建连失败置 ERROR，
 * 会话关闭置 CLOSED。审计失败不阻断终端主流程（调用方自行吞掉异常）。
 */
public interface TerminalAuditMapper
{
    int insertAttempt(TerminalSessionRecord record);

    int markError(@Param("sessionUuid") String sessionUuid, @Param("errorCode") String errorCode,
            @Param("closedAt") Instant closedAt);

    int finishAttempt(@Param("sessionUuid") String sessionUuid, @Param("closeReason") String closeReason,
            @Param("closedAt") Instant closedAt);
}
