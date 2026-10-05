package com.ruoyi.train.core.gpu.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;
import com.ruoyi.train.core.gpu.domain.GpuDeviceDecision;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;
import com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

/**
 * Validates an authenticated GPU discovery report into fail-closed catalog decisions.
 * It never returns "free" on stale, conflicting, MIG or externally occupied devices.
 */
@Component
public final class GpuSnapshotValidator
{
    private final Duration maxAge;
    private final Duration futureTolerance;

    public GpuSnapshotValidator()
    {
        this(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    public GpuSnapshotValidator(Duration maxAge, Duration futureTolerance)
    {
        this.maxAge = Objects.requireNonNull(maxAge);
        this.futureTolerance = Objects.requireNonNull(futureTolerance);
    }

    public ValidatedGpuReport validate(String authenticatedRunnerId, GpuDiscoveryReport report,
            Instant receivedAt, Set<Long> ownedPids)
    {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(receivedAt, "receivedAt");
        final long runnerId = resolveRunner(authenticatedRunnerId, report);
        Instant sampledAt = report.discoveredAt();
        if (sampledAt.isBefore(receivedAt.minus(maxAge)))
            throw error(RunnerErrorCode.GPU_SNAPSHOT_STALE, "GPU snapshot is stale");
        if (sampledAt.isAfter(receivedAt.plus(futureTolerance)))
            throw error(RunnerErrorCode.GPU_SNAPSHOT_STALE, "GPU snapshot clock is too far in the future");
        if (report.status() != com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryStatus.OK)
            return new ValidatedGpuReport(runnerId, report.hostSignature(), false, List.of());

        Set<String> seen = new HashSet<>();
        List<GpuDeviceDecision> decisions = new ArrayList<>();
        for (GpuSnapshot snapshot : report.gpus())
        {
            if (!seen.add(snapshot.uuid()))
                throw error(RunnerErrorCode.GPU_SNAPSHOT_INVALID, "Duplicate GPU UUID in one report");
            decisions.add(decide(report.hostSignature(), snapshot, ownedPids == null ? Set.of() : ownedPids));
        }
        return new ValidatedGpuReport(runnerId, report.hostSignature(), true, decisions);
    }

    private static long resolveRunner(String authenticatedRunnerId, GpuDiscoveryReport report)
    {
        if (authenticatedRunnerId == null || !authenticatedRunnerId.equals(report.runnerId()))
            throw error(RunnerErrorCode.RUNNER_AUTH_INVALID, "Authenticated runner does not match report");
        try
        {
            return Long.parseLong(authenticatedRunnerId);
        }
        catch (NumberFormatException failure)
        {
            throw error(RunnerErrorCode.RUNNER_AUTH_INVALID, "Runner id is not numeric");
        }
    }

    private static GpuDeviceDecision decide(String hostSignature, GpuSnapshot snapshot, Set<Long> ownedPids)
    {
        boolean mig = snapshot.uuid().startsWith("MIG-")
                || "ENABLED".equalsIgnoreCase(snapshot.migMode())
                || snapshot.computeProcesses().stream().anyMatch(p -> p.gpuInstanceId() != null);
        List<Long> externalPids = snapshot.computeProcesses().stream()
                .map(GpuProcessInfo::pid).filter(pid -> !ownedPids.contains(pid)).distinct().toList();
        boolean external = !externalPids.isEmpty();
        DeviceIdentityState state =
                mig ? DeviceIdentityState.MIG_UNSUPPORTED
                    : external ? DeviceIdentityState.EXTERNAL_OCCUPIED
                        : DeviceIdentityState.OBSERVED;
        return new GpuDeviceDecision(snapshot.uuid(), hostSignature, snapshot.name(),
                snapshot.visibleIndex(), state, state.isSchedulable(),
                snapshot.totalMemoryMiB(), snapshot.usedMemoryMiB(), snapshot.utilizationPercent(),
                snapshot.migMode(), snapshot.sampledAt(), externalPids);
    }

    private static RunnerProtocolException error(RunnerErrorCode code, String message)
    {
        return new RunnerProtocolException(code, message);
    }
}
