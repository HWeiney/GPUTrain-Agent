package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;

public interface RunnerCommandMapper
{
    int insert(RunnerCommandRecord command);

    RunnerCommandRecord findById(@Param("commandId") Long commandId);

    RunnerCommandRecord findByUuid(@Param("commandUuid") String commandUuid);

    RunnerCommandRecord findByRunnerAndIdempotencyKey(@Param("runnerId") Long runnerId,
            @Param("idempotencyKey") String idempotencyKey);

    Long selectLeaseCandidateForUpdate(@Param("runnerId") Long runnerId,
            @Param("now") Instant now);

    int markLeased(@Param("commandId") Long commandId,
            @Param("now") Instant now, @Param("leasedUntil") Instant leasedUntil);

    /** The SELECT lock and update must share one database transaction. */
    @Transactional
    default RunnerCommandRecord leaseNext(Long runnerId, Instant now, Instant leasedUntil)
    {
        Long commandId = selectLeaseCandidateForUpdate(runnerId, now);
        if (commandId == null)
            return null;
        if (markLeased(commandId, now, leasedUntil) != 1)
            throw new IllegalStateException("Locked command was not leased: " + commandId);
        return findById(commandId);
    }

    int markRunning(@Param("commandId") Long commandId,
            @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    int complete(@Param("commandId") Long commandId,
            @Param("status") String status, @Param("result") String result,
            @Param("errorCode") String errorCode, @Param("now") Instant now);

    int markRejected(@Param("commandId") Long commandId, @Param("now") Instant now,
            @Param("result") String result, @Param("errorCode") String errorCode,
            @Param("leaseUntil") Instant leaseUntil);
}
