package com.ruoyi.train.core.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.server.mapper.ServerInventoryMapper;

class TrainOverviewServiceTest
{
    @Test
    void overviewCountsOwnedActiveJobsAndOnlineServers()
    {
        JobMapper jobs = (JobMapper) Proxy.newProxyInstance(JobMapper.class.getClassLoader(),
                new Class<?>[] { JobMapper.class }, (proxy, method, args) ->
                    "countActive".equals(method.getName()) ? 2 : null);
        ServerInventoryMapper servers = (ServerInventoryMapper) Proxy.newProxyInstance(
                ServerInventoryMapper.class.getClassLoader(), new Class<?>[] { ServerInventoryMapper.class },
                (proxy, method, args) -> "countOnline".equals(method.getName()) ? 3 : null);
        TrainOverview overview = new TrainOverviewService(jobs, servers).getOverview(42L, false);
        assertEquals(2, overview.activeExperiments());
        assertEquals(3, overview.onlineServers());
    }
}
