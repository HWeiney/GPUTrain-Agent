package com.ruoyi.train.core.overview;

import java.time.Instant;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.server.mapper.ServerInventoryMapper;

@Service
public class TrainOverviewService
{
    private final ServerInventoryMapper servers;
    private final JobMapper jobs;

    public TrainOverviewService(JobMapper jobs, ServerInventoryMapper servers)
    {
        this.jobs = jobs;
        this.servers = servers;
    }

    public TrainOverview getOverview()
    {
        long userId = SecurityUtils.getUserId();
        return getOverview(userId, SecurityUtils.isAdmin(userId));
    }

    public TrainOverview getOverview(long userId, boolean isAdmin)
    {
        int active = jobs.countActive(userId, isAdmin);
        Instant now = Instant.now();
        int onlineServers = servers.countOnline(userId, isAdmin, now.minusSeconds(30), now);
        return new TrainOverview("READY", active, onlineServers);
    }
}
