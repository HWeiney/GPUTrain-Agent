package com.ruoyi.train.core.runner.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.runner.domain.RunnerEnrollmentRecord;

public interface RunnerEnrollmentMapper
{
    int insert(RunnerEnrollmentRecord enrollment);

    RunnerEnrollmentRecord findById(@Param("enrollmentId") Long enrollmentId);

    RunnerEnrollmentRecord findByTokenDigest(@Param("tokenDigest") String tokenDigest);

    int consume(@Param("enrollmentId") Long enrollmentId,
            @Param("tokenDigest") String tokenDigest,
            @Param("consumedAt") Instant consumedAt);
}
