package com.ruoyi.train.core.gpu.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;

class GpuCandidateSelectorTest
{
    private GpuAllocationCandidate c(String uuid, String host, long mem, boolean schedulable,
            boolean fresh, DeviceIdentityState state)
    {
        return new GpuAllocationCandidate(uuid, host, mem, schedulable, fresh, state);
    }

    private GpuAllocationCandidate good(String uuid, String host, long mem)
    {
        return c(uuid, host, mem, true, true, DeviceIdentityState.OBSERVED);
    }

    private GpuAllocationRequest req(int count, long minMem, boolean sameHost,
            Set<String> allowed, Set<String> denied)
    {
        return new GpuAllocationRequest(count, minMem, allowed, denied, sameHost);
    }

    @Test void selectsSingleSchedulableFreshDevice()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-b", "h1", 80000), good("GPU-a", "h1", 80000)),
                req(1, 40000, false, Set.of(), Set.of()));
        assertEquals(1, chosen.size());
        assertEquals("GPU-a", chosen.get(0).gpuUuid()); // deterministic ordering
    }

    @Test void requiresMinimumMemory()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-small", "h1", 20000), good("GPU-big", "h1", 80000)),
                req(1, 40000, false, Set.of(), Set.of()));
        assertEquals(List.of("GPU-big"), chosen.stream().map(GpuAllocationCandidate::gpuUuid).toList());
    }

    @Test void honorsAllowList()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-a", "h1", 80000), good("GPU-b", "h1", 80000)),
                req(1, 1, false, Set.of("GPU-b"), Set.of()));
        assertEquals(List.of("GPU-b"), chosen.stream().map(GpuAllocationCandidate::gpuUuid).toList());
    }

    @Test void honorsDenyList()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-a", "h1", 80000), good("GPU-b", "h1", 80000)),
                req(1, 1, false, Set.of(), Set.of("GPU-a")));
        assertEquals(List.of("GPU-b"), chosen.stream().map(GpuAllocationCandidate::gpuUuid).toList());
    }

    @Test void multiCardIsAllOrNothingWhenTooFew()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-a", "h1", 80000)),
                req(2, 1, true, Set.of(), Set.of()));
        assertTrue(chosen.isEmpty());
    }

    @Test void multiCardRequiresSameHost()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-a", "h1", 80000), good("GPU-b", "h2", 80000)),
                req(2, 1, true, Set.of(), Set.of()));
        assertTrue(chosen.isEmpty());
    }

    @Test void multiCardSucceedsOnSameHostSorted()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(good("GPU-b", "h1", 80000), good("GPU-a", "h1", 80000),
                        good("GPU-c", "h2", 80000)),
                req(2, 1, true, Set.of(), Set.of()));
        assertEquals(List.of("GPU-a", "GPU-b"), chosen.stream().map(GpuAllocationCandidate::gpuUuid).toList());
    }

    @Test void nonSchedulableStaleOrBadStateDevicesAreExcluded()
    {
        GpuCandidateSelector selector = new GpuCandidateSelector();
        GpuAllocationCandidate stale = c("GPU-stale", "h1", 80000, true, false,
                DeviceIdentityState.OBSERVED);
        GpuAllocationCandidate occupied = c("GPU-occ", "h1", 80000, false, true,
                DeviceIdentityState.EXTERNAL_OCCUPIED);
        GpuAllocationCandidate mig = c("GPU-mig", "h1", 80000, false, true,
                DeviceIdentityState.MIG_UNSUPPORTED);
        List<GpuAllocationCandidate> chosen = selector.select(
                List.of(stale, occupied, mig, good("GPU-ok", "h1", 80000)),
                req(1, 1, false, Set.of(), Set.of()));
        assertEquals(List.of("GPU-ok"), chosen.stream().map(GpuAllocationCandidate::gpuUuid).toList());
    }
}
