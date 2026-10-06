package com.ruoyi.train.core.gpu.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.train.core.gpu.domain.GpuLeaseRecord;
import com.ruoyi.train.core.gpu.mapper.GpuLeaseMapper;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

/**
 * Acquires, renews and releases GPU leases atomically.
 * The database unique constraint on the active GPU column guarantees at most one
 * active lease per GPU; a multi-card request is committed entirely or rolled back.
 */
@Service
public class GpuLeaseService
{
    private static final Duration FRESHNESS = Duration.ofSeconds(30);
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);

    private final GpuLeaseMapper leaseMapper;
    private final GpuCandidateSelector selector;

    public GpuLeaseService(GpuLeaseMapper leaseMapper, GpuCandidateSelector selector)
    {
        this.leaseMapper = Objects.requireNonNull(leaseMapper);
        this.selector = Objects.requireNonNull(selector);
    }

    @Transactional
    public List<GpuLeaseRecord> allocate(long jobId, GpuAllocationRequest request,
            String idempotencyKey, Instant now)
    {
        Objects.requireNonNull(request, "request");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("idempotencyKey is required");
        List<GpuLeaseRecord> existing = leaseMapper.findByJobAndKey(jobId, idempotencyKey);
        if (!existing.isEmpty())
            return existing;

        List<GpuAllocationCandidate> candidates =
                leaseMapper.lockCandidates(now.minus(FRESHNESS), request.ownerUserId());
        List<GpuAllocationCandidate> chosen = selector.select(candidates, request);
        if (chosen.size() < request.gpuCount())
            throw new RunnerProtocolException(RunnerErrorCode.GPU_UNOBSERVABLE,
                    "No set of GPUs satisfies the request");

        List<GpuLeaseRecord> leases = new ArrayList<>();
        Instant expires = now.plus(DEFAULT_LEASE);
        for (GpuAllocationCandidate device : chosen)
        {
            String deviceKey = idempotencyKey + ":" + device.gpuUuid();
            GpuLeaseRecord lease = new GpuLeaseRecord(null, UUID.randomUUID().toString(), jobId,
                    device.runnerId(), device.gpuUuid(), "ACTIVE", deviceKey,
                    now, expires, null, null, 0, now, now);
            leaseMapper.insert(lease);
            leases.add(lease);
        }
        return leases;
    }

    public List<GpuLeaseRecord> findActiveByJob(long jobId)
    {
        return leaseMapper.findActiveByJob(jobId);
    }

    @Transactional
    public void renew(long leaseId, long jobId, Instant now)
    {
        if (leaseMapper.conditionalRenew(leaseId, jobId, "ACTIVE",
                now.plus(DEFAULT_LEASE), now) != 1)
            throw new RunnerProtocolException(RunnerErrorCode.COMMAND_LEASE_EXPIRED,
                    "Lease cannot be renewed");
    }

    @Transactional
    public void release(long leaseId, long jobId, Instant now)
    {
        leaseMapper.conditionalRelease(leaseId, jobId, "ACTIVE", now);
    }

    @Transactional
    public void expireOverdue(Instant now)
    {
        leaseMapper.markExpiredQuarantined(now);
    }
}
