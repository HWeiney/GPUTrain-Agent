package com.ruoyi.train.core.server.gpu;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SshGpuLeaseMapper
{
    int insert(SshGpuLeaseRecord lease);

    List<SshGpuLeaseRecord> findByJob(@Param("jobId") long jobId);

    SshGpuLeaseRecord findActiveByServerGpu(@Param("serverId") long serverId,
            @Param("gpuUuid") String gpuUuid);

    int release(@Param("leaseId") long leaseId, @Param("releasedAt") Instant releasedAt);
}
