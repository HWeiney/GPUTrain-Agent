package com.ruoyi.train.core.server.ssh;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;

class SshNvidiaSmiParserTest
{
    private final SshNvidiaSmiParser parser = new SshNvidiaSmiParser();
    private final Instant now = Instant.parse("2026-10-09T00:00:00Z");

    @Test void preservesUnknownExternalProcessesAndMig()
    {
        var result = parser.parse("GPU-same, NVIDIA A100, 0, 80000, 1000, 20, disabled, 550.1\n"
            + "MIG-one, NVIDIA A100, 1, 40000, 0, 0, enabled, 550.1\n",
            "GPU-same, 123, [Not Found], 1000\n", now);
        assertEquals(2, result.gpus().size());
        assertEquals("550.1", result.driverVersion());
        assertEquals("DISABLED", result.gpus().get(0).migMode());
        assertEquals(1, result.gpus().get(0).computeProcesses().size());
        assertEquals(DeviceIdentityState.EXTERNAL_OCCUPIED, SshNvidiaSmiParser.state(result.gpus().get(0)));
        assertEquals(DeviceIdentityState.MIG_UNSUPPORTED, SshNvidiaSmiParser.state(result.gpus().get(1)));
    }

    @Test void rejectsCorruptDuplicateAndUnmatchedProcessRows()
    {
        String gpu = "GPU-same, A100, 0, 80000, 0, 0, Disabled, 550\n";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(gpu + gpu, "", now));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(gpu, "GPU-same, 1, unknown, N/A", now));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(gpu, "GPU-other, 1, unknown, 1", now));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(gpu.replace(", 0, 80000", ", 4294967296, 80000"), "", now));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(gpu.replace(", 0, Disabled", ", 101, Disabled"), "", now));
    }

    @Test void unknownMigModeCannotBecomeSchedulable()
    {
        var gpu = parser.parse("GPU-same, A100, 0, 80000, 0, 0, unknown, 550", "", now).gpus().get(0);
        assertEquals(DeviceIdentityState.UNOBSERVABLE, SshNvidiaSmiParser.state(gpu));
    }
}
