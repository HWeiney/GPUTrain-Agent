package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

class RunnerHeartbeatServiceTest
{
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private final List<Object> updates = new ArrayList<>();
    private final RunnerRecord runner = new RunnerRecord(1L, "runner", "linux", "/tmp",
            "encrypted", "ACTIVE", null, NOW, NOW);
    private final RunnerMapper mapper = (RunnerMapper) Proxy.newProxyInstance(RunnerMapper.class.getClassLoader(),
            new Class<?>[] { RunnerMapper.class }, (proxy, method, args) -> switch (method.getName())
            {
                case "findById" -> runner;
                case "updateHeartbeat" -> { updates.add(args[0]); updates.add(args[1]); yield 1; }
                default -> null;
            });
    private final List<Object> redisWrites = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final ValueOperations<Object, Object> values = (ValueOperations<Object, Object>) Proxy.newProxyInstance(
            ValueOperations.class.getClassLoader(), new Class<?>[] { ValueOperations.class },
            (proxy, method, args) -> { if (method.getName().equals("set")) redisWrites.addAll(List.of(args)); return null; });
    private final RedisTemplate<Object, Object> redis = new RedisTemplate<>()
    {
        @Override
        public ValueOperations<Object, Object> opsForValue() { return values; }
    };
    private final RunnerHeartbeatService service = new RunnerHeartbeatService(mapper, redis);

    @Test
    void recordsAuthenticatedHeartbeatAndNinetySecondPresence()
    {
        service.record("1", new RunnerHeartbeat("1", "linux", NOW.minusSeconds(2)), NOW);
        assertEquals(List.of(1L, NOW), updates);
        assertEquals(List.of("gputrain:runner:online:1", "1", Duration.ofSeconds(90)), redisWrites);
    }

    @Test
    void rejectsBodyIdentityMismatchBeforeUpdatingAnything()
    {
        RunnerProtocolException error = assertThrows(RunnerProtocolException.class,
                () -> service.record("1", new RunnerHeartbeat("2", "linux", NOW), NOW));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, error.code());
        assertEquals(List.of(), updates);
        assertEquals(List.of(), redisWrites);
    }

    @Test
    void rejectsWrongLinuxUsername()
    {
        RunnerProtocolException error = assertThrows(RunnerProtocolException.class,
                () -> service.record("1", new RunnerHeartbeat("1", "other", NOW), NOW));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, error.code());
        assertEquals(List.of(), updates);
    }
}
