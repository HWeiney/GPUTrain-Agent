package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.runner.domain.RunnerRecord;

public interface RunnerMapper
{
    int insert(RunnerRecord runner);

    RunnerRecord findById(@Param("runnerId") Long runnerId);

    RunnerRecord findByName(@Param("runnerName") String runnerName);

    List<Long> listActiveIdsForOwner(@Param("ownerUserId") Long ownerUserId,
            @Param("isAdmin") boolean isAdmin, @Param("afterRunnerId") long afterRunnerId,
            @Param("limit") int limit);

    int updateHeartbeat(@Param("runnerId") Long runnerId,
            @Param("lastSeenAt") Instant lastSeenAt);
}
