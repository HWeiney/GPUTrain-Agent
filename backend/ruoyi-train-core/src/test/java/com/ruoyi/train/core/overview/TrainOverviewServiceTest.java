package com.ruoyi.train.core.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;

class TrainOverviewServiceTest
{
    @Test
    void countsVisibleActiveJobsAndOnlineRunners()
    {
        JobMapper jobs = mapper(JobMapper.class, 2);
        RunnerMapper runners = mapper(RunnerMapper.class, List.of(1L, 2L));
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
        RunnerMapper runners = mapper(RunnerMapper.class, List.of(1L));
        RedisTemplate<Object, Object> redis = new RedisTemplate<>() {
            @Override public Boolean hasKey(Object key) { throw new IllegalStateException("Redis unavailable"); }
        };
        assertThrows(IllegalStateException.class, () -> new TrainOverviewService(jobs, runners, redis).getOverview(42L, false));
    }

    @Test
    void countsOnlineRunnersBeyondFirstPage()
    {
        JobMapper jobs = mapper(JobMapper.class, 0);
        @SuppressWarnings("unchecked")
        RunnerMapper runners = (RunnerMapper) Proxy.newProxyInstance(RunnerMapper.class.getClassLoader(),
                new Class<?>[] { RunnerMapper.class }, (proxy, method, args) ->
                {
                    if ((long) args[2] == 0L) return LongStream.rangeClosed(1, 200).boxed().toList();
                    assertEquals(200L, args[2]);
                    return List.of(201L);
                });
        RedisTemplate<Object, Object> redis = new RedisTemplate<>() {
            @Override public Boolean hasKey(Object key) { return key.equals("gputrain:runner:online:201"); }
        };
        assertEquals(1, new TrainOverviewService(jobs, runners, redis).getOverview(42L, false).onlineRunners());
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> type, Object result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> result);
    }
}
