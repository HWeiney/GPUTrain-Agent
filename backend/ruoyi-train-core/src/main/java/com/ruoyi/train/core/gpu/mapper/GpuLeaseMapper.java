package com.ruoyi.train.core.gpu.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.gpu.domain.GpuLeaseRecord;
import com.ruoyi.train.core.gpu.service.GpuAllocationCandidate;

public interface GpuLeaseMapper
{
    int insert(GpuLeaseRecord lease);

    List<GpuLeaseRecord> findByJobAndKey(@Param("jobId") Long jobId,
            @Param("idempotencyKey") String idempotencyKey);

    List<GpuLeaseRecord> findActiveByJob(@Param("jobId") Long jobId);

    /** Locks currently leasable devices, excluding any GPU with an ACTIVE lease. */
    List<GpuAllocationCandidate> lockCandidates(@Param("freshAfter") Instant freshAfter);

    /** Resolves the Runner that most recently observed a device (FK source for the lease). */
    Long findRunnerIdForGpu(@Param("gpuUuid") String gpuUuid);

    int conditionalRenew(@Param("leaseId") Long leaseId, @Param("jobId") Long jobId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newExpiresAt") Instant newExpiresAt,
            @Param("renewedAt") Instant renewedAt);

    int conditionalRelease(@Param("leaseId") Long leaseId, @Param("jobId") Long jobId,
            @Param("expectedStatus") String expectedStatus,
            @Param("releasedAt") Instant releasedAt);

    /** Moves ACTIVE leases past their expiry into fail-closed quarantine (never free). */
    int markExpiredQuarantined(@Param("now") Instant now);
}
