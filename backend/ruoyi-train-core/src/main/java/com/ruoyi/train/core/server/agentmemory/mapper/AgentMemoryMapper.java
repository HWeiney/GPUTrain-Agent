package com.ruoyi.train.core.server.agentmemory.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.agentmemory.domain.AgentMemoryRecord;
import com.ruoyi.train.core.server.agentmemory.domain.MemoryTurnView;

/**
 * Agent 会话长记忆持久化：insert 为 append-only；
 * history 以记忆行 LEFT JOIN 提案表取结果（状态/exitCode/stdout 摘要），按时间倒序取最近 N 条。
 */
public interface AgentMemoryMapper
{
    int insert(AgentMemoryRecord record);

    List<MemoryTurnView> history(@Param("actorUserId") long actorUserId,
            @Param("serverId") long serverId, @Param("limit") int limit);
}
