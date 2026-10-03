package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.runner.domain.RunnerProcessRecord;

public interface RunnerProcessMapper
{
    int insert(RunnerProcessRecord process);

    RunnerProcessRecord findByCommandId(@Param("commandId") Long commandId);

    int updateSnapshot(@Param("commandId") Long commandId,
            @Param("status") String status, @Param("exitCode") Integer exitCode,
            @Param("errorCode") String errorCode,
            @Param("lastSeenAt") Instant lastSeenAt);
}
