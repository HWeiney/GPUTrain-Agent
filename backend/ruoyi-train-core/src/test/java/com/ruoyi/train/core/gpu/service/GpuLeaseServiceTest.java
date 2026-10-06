package com.ruoyi.train.core.gpu.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;
import com.ruoyi.train.core.gpu.domain.GpuLeaseRecord;
import com.ruoyi.train.core.gpu.mapper.GpuLeaseMapper;

class GpuLeaseServiceTest
{
    @Test void multiGpuLeaseKeysAreUniqueAndRetryLoadsTheWholeGroup()
    {
        List<GpuLeaseRecord> inserted = new ArrayList<>();
        int[] findCalls = {0};
        int[] candidateCalls = {0};
        GpuLeaseMapper mapper = (GpuLeaseMapper) Proxy.newProxyInstance(
                GpuLeaseMapper.class.getClassLoader(), new Class<?>[] { GpuLeaseMapper.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByJobAndKey" -> findCalls[0]++ == 0 ? List.of() : List.copyOf(inserted);
                    case "lockCandidates" -> { candidateCalls[0]++; yield candidates(); }
                    case "insert" -> { inserted.add((GpuLeaseRecord) args[0]); yield 1; }
                    case "toString" -> "test mapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        GpuLeaseService service = new GpuLeaseService(mapper, new GpuCandidateSelector());
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        List<GpuAllocationCandidate> candidates = List.of(
                candidate("GPU-00000001"), candidate("GPU-00000002"), candidate("GPU-00000003"));
        var request = new GpuAllocationRequest(1L, 3, 0, Set.of(), Set.of(), true, 0, true);
        List<GpuLeaseRecord> first = service.allocate(9L, request, "lease:req-9", now);
        assertEquals(3, first.size());
        assertEquals(3, first.stream().map(GpuLeaseRecord::idempotencyKey).distinct().count());
        assertEquals(3, service.allocate(9L, request, "lease:req-9", now).size());
        assertEquals(3, inserted.size());
        assertEquals(1, candidateCalls[0]);
    }

    private static List<GpuAllocationCandidate> candidates()
    { return List.of(candidate("GPU-00000001"), candidate("GPU-00000002"), candidate("GPU-00000003")); }

    private static GpuAllocationCandidate candidate(String uuid)
    { return new GpuAllocationCandidate(uuid, "host", 100_000, 0, true, true, DeviceIdentityState.OBSERVED, 1L); }

    private static GpuLeaseRecord lease(String uuid, String key)
    { return new GpuLeaseRecord(1L, "lease-" + uuid, 9L, 1L, uuid, "ACTIVE", key,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, null, 0, Instant.EPOCH, Instant.EPOCH); }
}
