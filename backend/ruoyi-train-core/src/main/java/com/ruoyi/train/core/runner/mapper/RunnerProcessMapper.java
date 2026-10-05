package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.runner.domain.RunnerProcessRecord;

public interface RunnerProcessMapper
{
    int insert(RunnerProcessRecord process);

    RunnerProcessRecord findByCommandId(@Param("commandId") Long commandId);

    List<Long> listRunningPids(@Param("runnerId") Long runnerId);

    int updateSnapshot(@Param("commandId") Long commandId, @Param("runnerId") Long runnerId,
            @Param("expectedStatus") String expectedStatus,
            @Param("status") String status, @Param("exitCode") Integer exitCode,
            @Param("errorCode") String errorCode,
            @Param("lastSeenAt") Instant lastSeenAt);
}
