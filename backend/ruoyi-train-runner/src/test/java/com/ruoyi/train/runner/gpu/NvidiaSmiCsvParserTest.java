package com.ruoyi.train.runner.gpu;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiaSmiCsvParserTest
{
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private static final String GPU_CSV =
            "GPU-aaaaaaaa-0000-0000-0000-000000000000, NVIDIA A100-SXM4-40GB, 0, 40960, 1024, 37, Disabled, 550.54.15\n"
            + "GPU-bbbbbbbb-1111-1111-1111-111111111111, NVIDIA A100-SXM4-40GB, 1, 40960, 0, 0, Disabled, 550.54.15\n";

    private static final String COMPUTE_CSV =
            "GPU-aaaaaaaa-0000-0000-0000-000000000000, 12345, python, 1024\n";

    @Test
    void parsesGpusAndLinksProcesses()
    {
        Map<String, List<GpuProcessInfo>> processes =
                NvidiaSmiCsvParser.parseCompute(COMPUTE_CSV);
        List<GpuSnapshot> gpus = NvidiaSmiCsvParser.parseGpus(GPU_CSV, NOW, processes);
        assertEquals(2, gpus.size());
        GpuSnapshot first = gpus.get(0);
        assertEquals("GPU-aaaaaaaa-0000-0000-0000-000000000000", first.uuid());
        assertEquals(0, first.visibleIndex());
        assertEquals(40960, first.totalMemoryMiB());
        assertEquals(1024, first.usedMemoryMiB());
        assertEquals(37, first.utilizationPercent());
        assertEquals("DISABLED", first.migMode());
        assertEquals(1, first.computeProcesses().size());
        assertEquals(12345, first.computeProcesses().get(0).pid());
        assertEquals(1024, first.computeProcesses().get(0).usedMemoryMiB());
        assertTrue(gpus.get(1).computeProcesses().isEmpty());
    }

    @Test
    void parsesComputeRowsWithoutOptionalGpuInstanceId()
    {
        String compute = "GPU-aaaaaaaa-0000-0000-0000-000000000000, 12345, python, 1024\n";
        GpuProcessInfo process = NvidiaSmiCsvParser.parseCompute(compute)
                .get("GPU-aaaaaaaa-0000-0000-0000-000000000000").get(0);
        assertEquals(12345, process.pid());
        assertEquals(1024, process.usedMemoryMiB());
        assertEquals(null, process.gpuInstanceId());
    }

    @Test
    void extractsDriverVersion()
    {
        Map<String, List<GpuProcessInfo>> processes =
                NvidiaSmiCsvParser.parseCompute("");
        List<GpuSnapshot> gpus = NvidiaSmiCsvParser.parseGpus(GPU_CSV, NOW, processes);
        assertEquals("550.54.15", NvidiaSmiCsvParser.driverVersionFromCsv(GPU_CSV));
    }

    @Test
    void parsesEmptyOutputAsEmptyList()
    {
        assertTrue(NvidiaSmiCsvParser.parseGpus("", NOW, Map.of()).isEmpty());
        assertTrue(NvidiaSmiCsvParser.parseCompute("").isEmpty());
    }

    @Test
    void rejectsMissingFields()
    {
        String shortLine = "GPU-aaaaaaaa-0000-0000-0000-000000000000, A100, 0, 40960\n";
        assertThrows(IllegalArgumentException.class,
                () -> NvidiaSmiCsvParser.parseGpus(shortLine, NOW, Map.of()));
    }

    @Test
    void rejectsNonNumericMemory()
    {
        String bad = "GPU-aaaaaaaa-0000-0000-0000-000000000000, A100, 0, NaN, 0, 0, Disabled, 550\n";
        assertThrows(IllegalArgumentException.class,
                () -> NvidiaSmiCsvParser.parseGpus(bad, NOW, Map.of()));
    }

    @Test
    void rejectsMigLineAsWholeGpu()
    {
        // MIG compute instances carry MIG- ids; they must not be treated as whole GPUs.
        String mig = "MIG-cccccccc-2222-2222-2222-222222222222, MIG 1g.10gb, 0, 10240, 0, 0, Enabled, 550\n";
        List<GpuSnapshot> gpus = NvidiaSmiCsvParser.parseGpus(mig, NOW, Map.of());
        // The parser records the snapshot, but its MIG uuid/mode enables isolation upstream.
        assertTrue(gpus.get(0).uuid().startsWith("MIG-"));
        assertEquals("ENABLED", gpus.get(0).migMode());
    }

    @Test
    void rejectsDuplicateGpuUuid()
    {
        String dup =
                "GPU-aaaaaaaa-0000-0000-0000-000000000000, A100, 0, 40960, 0, 0, Disabled, 550\n"
                + "GPU-aaaaaaaa-0000-0000-0000-000000000000, A100, 1, 40960, 0, 0, Disabled, 550\n";
        assertThrows(IllegalArgumentException.class,
                () -> NvidiaSmiCsvParser.parseGpus(dup, NOW, Map.of()));
    }
}
