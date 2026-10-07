package com.ruoyi.train.core.overview;

import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;

@Service
public class TrainOverviewService
{
    private final JobMapper jobs;
    private final RunnerMapper runners;
    private final RedisTemplate<Object, Object> redis;

    public TrainOverviewService(JobMapper jobs, RunnerMapper runners, RedisTemplate<Object, Object> redis)
    {
        this.jobs = jobs;
        this.runners = runners;
        this.redis = redis;
    }

    public TrainOverview getOverview()
    {
        long userId = SecurityUtils.getUserId();
        return getOverview(userId, SecurityUtils.isAdmin(userId));
    }

    public TrainOverview getOverview(long userId, boolean isAdmin)
    {
        int active = jobs.countActive(userId, isAdmin);
        int online = 0;
        for (var runner : runners.listActiveForOwner(userId, isAdmin))
        {
            if (Boolean.TRUE.equals(redis.hasKey("gputrain:runner:online:" + runner.runnerId()))) online++;
        }
        return new TrainOverview("READY", active, online);
    }
}
