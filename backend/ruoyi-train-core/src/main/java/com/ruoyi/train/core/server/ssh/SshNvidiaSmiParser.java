package com.ruoyi.train.core.server.ssh;

import java.time.Instant;
import java.util.*;
import com.ruoyi.train.core.server.domain.GpuSnapshot;
import com.ruoyi.train.core.server.domain.GpuProcessInfo;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;

/** Fixed NVIDIA CSV contract, with bounded inventory fields and fail-closed occupancy. */
public final class SshNvidiaSmiParser
{
    public record Query(List<GpuSnapshot> gpus, String driverVersion)
    {
        public Query { gpus = List.copyOf(gpus); }
    }

    public Query parse(String gpuCsv, String processCsv, Instant now)
    {
        Map<String, List<GpuProcessInfo>> processes = new LinkedHashMap<>();
        for (String row : rows(processCsv, 512))
        {
            String[] f = fields(row, 4);
            text(f[0], 128); text(f[2], 255);
            processes.computeIfAbsent(f[0], key -> new ArrayList<>())
                .add(new GpuProcessInfo(number(f[1]), f[2], number(f[3]), null));
        }
        List<GpuSnapshot> gpus = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String driver = null;
        for (String row : rows(gpuCsv, 64))
        {
            String[] f = fields(row, 8);
            text(f[0], 128); text(f[1], 255); text(f[6], 32); text(f[7], 64);
            if (!seen.add(f[0])) throw invalid();
            if (driver == null) driver = f[7];
            if (!driver.equals(f[7])) throw invalid();
            gpus.add(new GpuSnapshot(f[0], f[1], integer(f[2]), number(f[3]), number(f[4]),
                integer(f[5]), f[6].toUpperCase(Locale.ROOT), now, processes.getOrDefault(f[0], List.of())));
        }
        // Unmatched/MIG process rows leave occupancy uncertain; never discard them as idle.
        if (!seen.containsAll(processes.keySet())) throw invalid();
        return new Query(gpus, driver);
    }

    public static DeviceIdentityState state(GpuSnapshot gpu)
    {
        if (gpu.uuid().startsWith("MIG-") || "ENABLED".equals(gpu.migMode())) return DeviceIdentityState.MIG_UNSUPPORTED;
        if (!Set.of("DISABLED", "N/A", "[N/A]").contains(gpu.migMode())) return DeviceIdentityState.UNOBSERVABLE;
        if (!gpu.computeProcesses().isEmpty()) return DeviceIdentityState.EXTERNAL_OCCUPIED;
        return DeviceIdentityState.OBSERVED;
    }

    private static List<String> rows(String csv, int maximum)
    {
        if (csv == null || csv.length() > 65536) throw invalid();
        List<String> rows = csv.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (rows.size() > maximum) throw invalid();
        return rows;
    }
    private static String[] fields(String row, int count)
    {
        String[] fields = row.split(",", -1);
        if (fields.length != count) throw invalid();
        for (int i = 0; i < fields.length; i++) fields[i] = fields[i].strip();
        return fields;
    }
    private static void text(String value, int maximum) { if (value.isBlank() || value.length() > maximum) throw invalid(); }
    private static long number(String value)
    {
        try { long n = Long.parseLong(value); if (n < 0) throw invalid(); return n; }
        catch (NumberFormatException e) { throw invalid(); }
    }
    private static int integer(String value) { long n = number(value); if (n > Integer.MAX_VALUE) throw invalid(); return (int) n; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CORRUPT_OUTPUT"); }
}
