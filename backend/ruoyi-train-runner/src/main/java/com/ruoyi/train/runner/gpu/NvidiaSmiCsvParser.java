package com.ruoyi.train.runner.gpu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

/** Parses fixed nvidia-smi CSV (noheader,nounits) into GPU and process objects. */
final class NvidiaSmiCsvParser
{
    private static final int GPU_FIELDS = 8;
    private static final int COMPUTE_FIELDS = 4;

    private NvidiaSmiCsvParser() { }

    /** Result of the GPU query: snapshots plus the shared driver version. */
    record GpuQueryResult(List<GpuSnapshot> gpus, String driverVersion) { }

    static Map<String, List<GpuProcessInfo>> parseCompute(String csv)
    {
        Map<String, List<GpuProcessInfo>> result = new LinkedHashMap<>();
        for (String line : lines(csv))
        {
            String[] fields = split(line);
            if (fields.length != COMPUTE_FIELDS)
                throw new IllegalArgumentException("compute-apps row must have " + COMPUTE_FIELDS + " fields");
            String gpuUuid = fields[0];
            long pid = parseLong(fields[1], "pid");
            String name = fields[2];
            long usedMemory = parseLong(fields[3], "used_memory");
            result.computeIfAbsent(gpuUuid, k -> new ArrayList<>())
                    .add(new GpuProcessInfo(pid, name, usedMemory, null));
        }
        return result;
    }

    static GpuQueryResult parseGpuQuery(String csv, Instant sampledAt,
            Map<String, List<GpuProcessInfo>> processesByGpu)
    {
        return new GpuQueryResult(parseGpus(csv, sampledAt, processesByGpu),
                driverVersionFromCsv(csv));
    }

    static List<GpuSnapshot> parseGpus(String csv, Instant sampledAt,
            Map<String, List<GpuProcessInfo>> processesByGpu)
    {
        List<GpuSnapshot> gpus = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines(csv))
        {
            String[] fields = split(line);
            if (fields.length != GPU_FIELDS)
                throw new IllegalArgumentException("gpu row must have " + GPU_FIELDS + " fields");
            String uuid = fields[0];
            if (!seen.add(uuid))
                throw new IllegalArgumentException("Duplicate GPU UUID in query: " + uuid);
            String name = fields[1];
            int index = (int) parseLong(fields[2], "index");
            long total = parseLong(fields[3], "memory.total");
            long used = parseLong(fields[4], "memory.used");
            int utilization = (int) parseLong(fields[5], "utilization.gpu");
            String mig = normalizeMig(fields[6]);
            gpus.add(new GpuSnapshot(uuid, name, index, total, used,
                    utilization, mig, sampledAt,
                    processesByGpu.getOrDefault(uuid, List.of())));
        }
        return gpus;
    }

    static String driverVersionFromCsv(String csv)
    {
        for (String line : lines(csv))
        {
            String[] fields = split(line);
            if (fields.length == GPU_FIELDS) return fields[7];
        }
        return null;
    }

    private static List<String> lines(String csv)
    {
        List<String> lines = new ArrayList<>();
        for (String raw : csv.split("\n", -1))
        {
            String line = raw.strip();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static String[] split(String line)
    {
        String[] raw = line.split(",", -1);
        String[] fields = new String[raw.length];
        for (int i = 0; i < raw.length; i++) fields[i] = raw[i].strip();
        return fields;
    }

    private static long parseLong(String value, String field)
    {
        try
        {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException(field + " must not be negative");
            return parsed;
        }
        catch (NumberFormatException failure)
        {
            throw new IllegalArgumentException("Invalid numeric value for " + field + ": " + value);
        }
    }

    private static String normalizeMig(String value)
    {
        return value.toUpperCase(java.util.Locale.ROOT);
    }

}
