package com.ruoyi.train.core.overview;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;

@Service
public class TrainOverviewService
{
    private final com.ruoyi.train.core.server.mapper.ServerInventoryMapper servers;
    private final JobMapper jobs;
    private final RunnerMapper runners;
    private final RedisTemplate<Object, Object> redis;

    public TrainOverviewService(JobMapper jobs, RunnerMapper runners, RedisTemplate<Object, Object> redis)
    {
        this(jobs, runners, redis, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TrainOverviewService(JobMapper jobs, RunnerMapper runners, RedisTemplate<Object, Object> redis,
            com.ruoyi.train.core.server.mapper.ServerInventoryMapper servers)
    {
        this.servers = servers;
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
        long afterRunnerId = 0;
        final int pageSize = 200;
        while (true)
        {
            List<Long> runnerIds = runners.listActiveIdsForOwner(userId, isAdmin, afterRunnerId, pageSize);
            for (Long runnerId : runnerIds)
            {
                if (Boolean.TRUE.equals(redis.hasKey("gputrain:runner:online:" + runnerId))) online++;
            }
            if (runnerIds.size() < pageSize) break;
            afterRunnerId = runnerIds.get(runnerIds.size() - 1);
        }
        java.time.Instant now = java.time.Instant.now();
        int onlineServers = servers == null ? 0 : servers.countOnline(userId, isAdmin, now.minusSeconds(30), now);
        return new TrainOverview("READY", active, online, onlineServers);
    }
}
