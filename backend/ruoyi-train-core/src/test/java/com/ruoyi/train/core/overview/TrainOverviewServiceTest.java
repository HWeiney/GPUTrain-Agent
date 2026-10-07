package com.ruoyi.train.core.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;

class TrainOverviewServiceTest
{
    @Test
    void countsVisibleActiveJobsAndOnlineRunners()
    {
        JobMapper jobs = mapper(JobMapper.class, 2);
        RunnerMapper runners = mapper(RunnerMapper.class, List.of(
                new RunnerRecord(1L, 42L, "one", "user", "/tmp", "secret", "ACTIVE", Instant.now(), Instant.now(), Instant.now()),
                new RunnerRecord(2L, 42L, "two", "user", "/tmp", "secret", "ACTIVE", Instant.now(), Instant.now(), Instant.now())));
        RedisTemplate<Object, Object> redis = new RedisTemplate<>() {
            @Override public Boolean hasKey(Object key) { return key.equals("gputrain:runner:online:1"); }
        };
        TrainOverview overview = new TrainOverviewService(jobs, runners, redis).getOverview(42L, false);

        assertEquals("READY", overview.status());
        assertEquals(2, overview.activeExperiments());
        assertEquals(1, overview.onlineRunners());
    }

    @Test
    void redisFailureDoesNotReportFalseReadyZeros()
    {
        JobMapper jobs = mapper(JobMapper.class, 0);
        RunnerMapper runners = mapper(RunnerMapper.class, List.of(
                new RunnerRecord(1L, 42L, "one", "user", "/tmp", "secret", "ACTIVE", Instant.now(), Instant.now(), Instant.now())));
        RedisTemplate<Object, Object> redis = new RedisTemplate<>() {
            @Override public Boolean hasKey(Object key) { throw new IllegalStateException("Redis unavailable"); }
        };
        assertThrows(IllegalStateException.class, () -> new TrainOverviewService(jobs, runners, redis).getOverview(42L, false));
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> type, Object result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> result);
    }
}
