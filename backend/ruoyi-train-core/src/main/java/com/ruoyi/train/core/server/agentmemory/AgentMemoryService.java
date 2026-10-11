package com.ruoyi.train.core.server.agentmemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.agentmemory.domain.AgentMemoryRecord;
import com.ruoyi.train.core.server.agentmemory.domain.MemoryTurnView;
import com.ruoyi.train.core.server.agentmemory.mapper.AgentMemoryMapper;

/**
 * Agent 会话长记忆：record 追加一轮交互（append-only）；
 * history 取本人 + 服务器的最近 N 轮（JOIN 提案表拿结果），返回时间正序供模型按时间线理解。
 */
@Service
public class AgentMemoryService
{
    /** 注入模型上下文的轮次上限（与前端「Agent 记忆」展示一致）。 */
    public static final int MAX_HISTORY_TURNS = 8;

    private final AgentMemoryMapper memory;

    public AgentMemoryService(AgentMemoryMapper memory)
    {
        this.memory = Objects.requireNonNull(memory);
    }

    public void record(AgentMemoryRecord record)
    {
        if (record == null || record.intentText() == null || record.intentText().isBlank()
                || record.action() == null || record.action().isBlank()
                || record.rationale() == null || record.rationale().isBlank())
            throw new ServiceException("INVALID_MEMORY_TURN");
        if (memory.insert(record) != 1) throw new IllegalStateException("Unable to insert agent memory turn");
    }

    public List<MemoryTurnView> history(long actorUserId, long serverId, int limit)
    {
        if (limit < 1 || limit > MAX_HISTORY_TURNS)
            throw new ServiceException("INVALID_MEMORY_LIMIT");
        List<MemoryTurnView> turns = memory.history(actorUserId, serverId, limit);
        List<MemoryTurnView> chronological = new ArrayList<>(turns);
        Collections.reverse(chronological);
        return Collections.unmodifiableList(chronological);
    }
}
