package com.ruoyi.train.core.runner.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Service
public class RunnerHeartbeatService
{
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);
    private final RunnerMapper runnerMapper;
    private final RedisTemplate<Object, Object> redisTemplate;

    public RunnerHeartbeatService(RunnerMapper runnerMapper, RedisTemplate<Object, Object> redisTemplate)
    {
        this.runnerMapper = Objects.requireNonNull(runnerMapper, "runnerMapper");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    public void record(String runnerId, RunnerHeartbeat heartbeat, Instant receivedAt)
    {
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (runnerId == null || !runnerId.equals(heartbeat.runnerId()))
            throw identityMismatch();
        final long numericId;
        try
        {
            numericId = Long.parseLong(runnerId);
        }
        catch (NumberFormatException failure)
        {
            throw identityMismatch();
        }
        RunnerRecord runner = runnerMapper.findById(numericId);
        if (runner == null || !"ACTIVE".equals(runner.status())
                || !runner.linuxUsername().equals(heartbeat.linuxUsername()))
            throw identityMismatch();
        if (runnerMapper.updateHeartbeat(numericId, receivedAt) != 1)
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID,
                    "Runner heartbeat could not be recorded");
        redisTemplate.opsForValue().set("gputrain:runner:online:" + runnerId, "1", ONLINE_TTL);
    }

    private static RunnerProtocolException identityMismatch()
    {
        return new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID,
                "Runner heartbeat identity mismatch");
    }
}
