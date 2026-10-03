package com.ruoyi.train.core.runner.security;

import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Component
public class RedisRunnerNonceStore implements RunnerNonceStore
{
    private final RedisTemplate<Object, Object> redisTemplate;

    public RedisRunnerNonceStore(RedisTemplate<Object, Object> redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean claim(String runnerId, String nonce, Duration ttl)
    {
        try
        {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    "gputrain:runner:nonce:" + runnerId + ":" + nonce, "1", ttl));
        }
        catch (RuntimeException failure)
        {
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID,
                    "Runner replay protection is unavailable");
        }
    }
}
