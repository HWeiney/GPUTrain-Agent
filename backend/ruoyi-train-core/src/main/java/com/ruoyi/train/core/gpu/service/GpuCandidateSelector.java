package com.ruoyi.train.core.gpu.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Deterministic, reproducible candidate selection.
 * It never partially allocates: a multi-card request returns the full ordered set
 * on a single host, or an empty list when constraints cannot be satisfied.
 */
@Component
public final class GpuCandidateSelector
{
    private static final Comparator<GpuAllocationCandidate> ORDER =
            Comparator.comparing(GpuAllocationCandidate::hostSignature)
                    .thenComparing(GpuAllocationCandidate::gpuUuid);

    public List<GpuAllocationCandidate> select(List<GpuAllocationCandidate> candidates,
            GpuAllocationRequest request)
    {
        List<GpuAllocationCandidate> eligible = candidates.stream()
                .filter(GpuAllocationCandidate::fresh)
                .filter(c -> c.identityState() == com.ruoyi.train.core.gpu.domain.DeviceIdentityState.OBSERVED
                        || request.allowExternalOccupancy() && c.identityState()
                            == com.ruoyi.train.core.gpu.domain.DeviceIdentityState.EXTERNAL_OCCUPIED)
                .filter(c -> c.schedulable() || request.allowExternalOccupancy()
                        && c.identityState() == com.ruoyi.train.core.gpu.domain.DeviceIdentityState.EXTERNAL_OCCUPIED)
                .filter(c -> c.totalMemoryMiB() >= request.minMemoryMiB())
                .filter(c -> c.totalMemoryMiB() - c.usedMemoryMiB() >= request.minFreeMemoryMiB())
                .filter(c -> request.allowedUuids().isEmpty()
                        || request.allowedUuids().contains(c.gpuUuid()))
                .filter(c -> !request.deniedUuids().contains(c.gpuUuid()))
                .sorted(request.allowExternalOccupancy()
                        ? Comparator.comparingLong((GpuAllocationCandidate c) -> c.totalMemoryMiB() - c.usedMemoryMiB())
                            .reversed().thenComparing(ORDER)
                        : ORDER)
                .toList();

        if (request.gpuCount() == 1)
            return eligible.isEmpty() ? List.of() : List.of(eligible.get(0));

        if (request.requireSameHost())
        {
            for (var group : eligible.stream().collect(Collectors.groupingBy(
                    GpuAllocationCandidate::hostSignature, LinkedHashMap::new, Collectors.toList())).values())
            {
                if (group.size() >= request.gpuCount())
                    return List.copyOf(group.subList(0, request.gpuCount()));
            }
            return List.of();
        }
        return eligible.size() >= request.gpuCount()
                ? List.copyOf(eligible.subList(0, request.gpuCount()))
                : List.of();
    }
}
