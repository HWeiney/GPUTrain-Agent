package com.ruoyi.train.core.gpu.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.gpu.domain.GpuAuditRecord;
import com.ruoyi.train.core.gpu.domain.GpuDeviceDecision;
import com.ruoyi.train.core.gpu.domain.GpuDeviceRecord;
import com.ruoyi.train.core.gpu.domain.GpuSnapshotRecord;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;
import com.ruoyi.train.core.gpu.mapper.GpuAuditMapper;
import com.ruoyi.train.core.gpu.mapper.GpuDeviceMapper;
import com.ruoyi.train.core.gpu.mapper.GpuSnapshotMapper;
import com.ruoyi.train.core.runner.mapper.RunnerProcessMapper;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;
import com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

/**
 * Persists validated GPU reports into the device catalog.
 * Observable facts are kept separate from leases; nothing is ever written as "free"
 * unless the validator confirms a clean identity, freshness and no external occupant.
 */
@Service
public class GpuDeviceCatalogService
{
    private final GpuSnapshotValidator validator;
    private final GpuDeviceMapper deviceMapper;
    private final GpuSnapshotMapper snapshotMapper;
    private final GpuAuditMapper auditMapper;
    private final RunnerProcessMapper processMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GpuDeviceCatalogService(GpuSnapshotValidator validator, GpuDeviceMapper deviceMapper,
            GpuSnapshotMapper snapshotMapper, GpuAuditMapper auditMapper,
            RunnerProcessMapper processMapper)
    {
        this.validator = validator;
        this.deviceMapper = deviceMapper;
        this.snapshotMapper = snapshotMapper;
        this.auditMapper = auditMapper;
        this.processMapper = processMapper;
    }

    @Transactional
    public void ingest(String authenticatedRunnerId, GpuDiscoveryReport report, Instant receivedAt)
    {
        Set<Long> ownedPids = Set.copyOf(processMapper.listRunningPids(
                parseRunnerId(authenticatedRunnerId)));
        ValidatedGpuReport validated =
                validator.validate(authenticatedRunnerId, report, receivedAt, ownedPids);
        Map<String, GpuSnapshot> reported = new LinkedHashMap<>();
        for (GpuSnapshot gpu : report.gpus()) reported.put(gpu.uuid(), gpu);

        List<GpuDeviceDecision> decisions = new ArrayList<>(validated.decisions());
        List<GpuAuditRecord> audits = new ArrayList<>();
        for (GpuDeviceDecision decision : decisions)
        {
            GpuDeviceDecision effective = reconcileHostConflict(decision);
            Instant now = receivedAt;
            deviceMapper.upsert(new GpuDeviceRecord(null, effective.gpuUuid(), effective.hostSignature(),
                    effective.name(), effective.identityState().name(), effective.schedulable(), now, now));
            snapshotMapper.insert(snapshot(validated.runnerId(), effective,
                    reported.get(effective.gpuUuid()), now));
            audits.add(audit(validated.runnerId(), effective, now));
        }
        // Devices previously known on this host but missing now become unobservable (fail closed).
        deviceMapper.markAbsentUnobservable(report.hostSignature(),
                decisions.stream().map(GpuDeviceDecision::gpuUuid).toList(), receivedAt);
        for (GpuAuditRecord audit : audits) auditMapper.insert(audit);
    }

    private GpuDeviceDecision reconcileHostConflict(GpuDeviceDecision decision)
    {
        GpuDeviceRecord existing = deviceMapper.findByUuid(decision.gpuUuid());
        if (existing != null && !existing.hostSignature().equals(decision.hostSignature())
                && existing.identityState() != DeviceIdentityState.UNOBSERVABLE.name())
        {
            return new GpuDeviceDecision(decision.gpuUuid(), decision.hostSignature(), decision.name(),
                    decision.visibleIndex(), DeviceIdentityState.IDENTITY_AMBIGUOUS, false,
                    decision.totalMemoryMiB(), decision.usedMemoryMiB(), decision.utilizationPercent(),
                    decision.migMode(), decision.sampledAt(), decision.externalPids());
        }
        return decision;
    }

    private GpuSnapshotRecord snapshot(long runnerId, GpuDeviceDecision decision,
            GpuSnapshot source, Instant now)
    {
        String processesJson = "[]";
        if (source != null)
        {
            try
            {
                processesJson = objectMapper.writeValueAsString(source.computeProcesses().stream()
                        .map(this::view).toList());
            }
            catch (Exception failure)
            {
                processesJson = "[]";
            }
        }
        return new GpuSnapshotRecord(null, runnerId, decision.gpuUuid(), decision.visibleIndex(),
                decision.totalMemoryMiB(), decision.usedMemoryMiB(), decision.utilizationPercent(),
                decision.migMode(), decision.sampledAt(), processesJson, now);
    }

    private Map<String, Object> view(GpuProcessInfo process)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pid", process.pid());
        map.put("processName", process.processName());
        map.put("usedMemoryMiB", process.usedMemoryMiB());
        map.put("gpuInstanceId", process.gpuInstanceId());
        return map;
    }

    private GpuAuditRecord audit(long runnerId, GpuDeviceDecision decision, Instant now)
    {
        return new GpuAuditRecord(null, now, null, null, runnerId, decision.gpuUuid(), null,
                "GPU_SNAPSHOT", decision.identityState().isSchedulable() ? "ACCEPT" : "REJECT",
                decision.identityState().name(),
                decision.externalPids().isEmpty() ? null
                        : "externalPids=" + decision.externalPids().stream()
                            .map(String::valueOf).collect(Collectors.joining(",")));
    }

    private static long parseRunnerId(String runnerId)
    {
        try
        {
            return Long.parseLong(runnerId);
        }
        catch (NumberFormatException failure)
        {
            throw new IllegalArgumentException("Runner id must be numeric");
        }
    }
}
