package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.runner.domain.RunnerRecord;

public interface RunnerMapper
{
    int insert(RunnerRecord runner);

    RunnerRecord findById(@Param("runnerId") Long runnerId);

    RunnerRecord findByName(@Param("runnerName") String runnerName);

    int updateHeartbeat(@Param("runnerId") Long runnerId,
            @Param("lastSeenAt") Instant lastSeenAt);
}
