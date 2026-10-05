package com.ruoyi.train.runner.protocol.gpu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuSnapshotProtocolTest
{
    private static final String UUID = "GPU-12345678-abcd-efgh-ijkl-1234567890ab";

    @Test
    void buildsValidSnapshot()
    {
        GpuSnapshot snapshot = new GpuSnapshot(UUID, "NVIDIA A100", 0, 40960, 1024,
                37, "DISABLED", Instant.now(), List.of());
        assertEquals(UUID, snapshot.uuid());
        assertEquals(37, snapshot.utilizationPercent());
        assertTrue(snapshot.computeProcesses().isEmpty());
    }

    @Test
    void rejectsBlankOrMalformedUuid()
    {
        assertThrows(IllegalArgumentException.class,
                () -> snapshotWithUuid(""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshotWithUuid("ORDINAL-0"));
        assertThrows(IllegalArgumentException.class,
                () -> snapshotWithUuid("GPU has spaces"));
    }

    @Test
    void rejectsOutOfRangeUtilization()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuSnapshot(UUID, "A100", 0, 1000, 0, -1, "DISABLED",
                        Instant.now(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuSnapshot(UUID, "A100", 0, 1000, 0, 101, "DISABLED",
                        Instant.now(), List.of()));
    }

    @Test
    void rejectsUsedGreaterThanTotal()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuSnapshot(UUID, "A100", 0, 1000, 2000, 0, "DISABLED",
                        Instant.now(), List.of()));
    }

    @Test
    void rejectsNegativeMemory()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuSnapshot(UUID, "A100", 0, -1, 0, 0, "DISABLED",
                        Instant.now(), List.of()));
    }

    @Test
    void rejectsBlankMigMode()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuSnapshot(UUID, "A100", 0, 1000, 0, 0, "",
                        Instant.now(), List.of()));
    }

    @Test
    void processesListIsImmutable()
    {
        List<GpuProcessInfo> processes = new ArrayList<>();
        processes.add(new GpuProcessInfo(123, "python", 500, null));
        GpuSnapshot snapshot = new GpuSnapshot(UUID, "A100", 0, 1000, 500, 0,
                "DISABLED", Instant.now(), processes);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.computeProcesses().add(new GpuProcessInfo(1, "x", 1, null)));
    }

    @Test
    void rejectsInvalidProcess()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuProcessInfo(0, "python", 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuProcessInfo(10, "python", -1, null));
    }

    @Test
    void reportRejectsTooManyGpus()
    {
        List<GpuSnapshot> many = new ArrayList<>();
        for (int i = 0; i < GpuDiscoveryReport.MAX_GPUS + 1; i++)
            many.add(snapshotWithUuid("GPU-" + String.format("%08x", i) + "-aaaa-bbbb-cccc-dddddddddddd"));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDiscoveryReport("1", "host", "550.1", "v550", "sig",
                        Instant.now(), GpuDiscoveryStatus.OK, many, null));
    }

    @Test
    void reportListIsImmutable()
    {
        GpuDiscoveryReport report = new GpuDiscoveryReport("1", "host", "v", "v", "sig",
                Instant.now(), GpuDiscoveryStatus.NO_DEVICES, List.of(), null);
        assertThrows(UnsupportedOperationException.class, () -> report.gpus().add(null));
    }

    @Test
    void reportRequiresRunnerId()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDiscoveryReport(" ", "host", "v", "v", "sig",
                        Instant.now(), GpuDiscoveryStatus.OK, List.of(), null));
    }

    private static GpuSnapshot snapshotWithUuid(String uuid)
    {
        return new GpuSnapshot(uuid, "A100", 0, 1000, 0, 0, "DISABLED",
                Instant.now(), List.of());
    }
}
