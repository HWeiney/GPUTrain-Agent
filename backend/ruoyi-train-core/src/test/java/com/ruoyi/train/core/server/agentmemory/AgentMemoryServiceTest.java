package com.ruoyi.train.core.server.agentmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.agentmemory.domain.AgentMemoryRecord;
import com.ruoyi.train.core.server.agentmemory.domain.MemoryTurnView;
import com.ruoyi.train.core.server.agentmemory.mapper.AgentMemoryMapper;

class AgentMemoryServiceTest
{
    private final FakeMemoryMapper memory = new FakeMemoryMapper();
    private final AgentMemoryService service = new AgentMemoryService(memory);

    @Test
    void recordAppendsValidatedTurn()
    {
        AgentMemoryRecord turn = turn("m-1", 1L, 4L, "看一下系统负载", "probe-loadavg", "p-1");

        service.record(turn);

        assertEquals(1, memory.rows.size());
        assertEquals(memory.rows.get(0), turn);
        // 缺意图 / 缺动作 / 缺依据均拒绝且不落库
        assertThrows(ServiceException.class, () -> service.record(turn("m-2", 1L, 4L, " ", "probe-loadavg", "r")));
        assertThrows(ServiceException.class, () -> service.record(turn("m-3", 1L, 4L, "i", null, "r")));
        assertThrows(ServiceException.class, () -> service.record(turn("m-4", 1L, 4L, "i", "probe-loadavg", " ")));
        assertThrows(ServiceException.class, () -> service.record(null));
        assertEquals(1, memory.rows.size(), "校验失败不得落库");
    }

    @Test
    void historyScopesToActorAndServerAndReversesToChronological()
    {
        memory.historyResult = List.of(
                view("m-9", "第三轮", "probe-disk", "EXECUTED", 0),
                view("m-8", "第二轮", "probe-meminfo", "EXECUTED", 0),
                view("m-7", "第一轮", "probe-loadavg", "PENDING", null));

        List<MemoryTurnView> turns = service.history(1L, 4L, 8);

        assertEquals("第一轮", turns.get(0).intentText());
        assertEquals("第二轮", turns.get(1).intentText());
        assertEquals("第三轮", turns.get(2).intentText());
        assertEquals(1L, memory.lastActorUserId);
        assertEquals(4L, memory.lastServerId);
        assertEquals(8, memory.lastLimit);
        assertThrows(UnsupportedOperationException.class, () -> turns.add(view("m-x", "i", "a", null, null)),
                "history 视图不可变");
    }

    @Test
    void historyRejectsInvalidLimit()
    {
        assertThrows(ServiceException.class, () -> service.history(1L, 4L, 0));
        assertThrows(ServiceException.class, () -> service.history(1L, 4L, AgentMemoryService.MAX_HISTORY_TURNS + 1));
    }

    private static AgentMemoryRecord turn(String memoryId, long actorUserId, long serverId,
            String intentText, String action, String rationale)
    {
        return new AgentMemoryRecord(memoryId, actorUserId, serverId, intentText, action, "{}", rationale,
                "p-" + memoryId, Instant.now());
    }

    private static MemoryTurnView view(String memoryId, String intentText, String action, String status, Integer exitCode)
    {
        return new MemoryTurnView(memoryId, intentText, action, status, exitCode, null, Instant.now());
    }

    private static class FakeMemoryMapper implements AgentMemoryMapper
    {
        final List<AgentMemoryRecord> rows = new ArrayList<>();
        List<MemoryTurnView> historyResult = List.of();
        long lastActorUserId;
        long lastServerId;
        int lastLimit;

        @Override
        public int insert(AgentMemoryRecord record)
        {
            rows.add(record);
            return 1;
        }

        @Override
        public List<MemoryTurnView> history(long actorUserId, long serverId, int limit)
        {
            lastActorUserId = actorUserId;
            lastServerId = serverId;
            lastLimit = limit;
            return historyResult;
        }
    }

    @Test
    void serviceRejectsInsertFailure()
    {
        AgentMemoryMapper failing = new FakeMemoryMapper()
        {
            @Override
            public int insert(AgentMemoryRecord record) { return 0; }
        };
        AgentMemoryService failingService = new AgentMemoryService(failing);
        assertThrows(IllegalStateException.class,
                () -> failingService.record(turn("m-1", 1L, 4L, "i", "probe-loadavg", "r")));
    }
}
