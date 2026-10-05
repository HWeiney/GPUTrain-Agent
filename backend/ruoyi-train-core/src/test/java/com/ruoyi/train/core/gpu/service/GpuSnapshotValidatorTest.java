package com.ruoyi.train.core.gpu.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.gpu.domain.GpuDeviceDecision;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryStatus;
import com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

class GpuSnapshotValidatorTest
{
    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final Duration FRESH = Duration.ofSeconds(30);
    private static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(60);

    private GpuSnapshot gpu(String uuid, String migMode, GpuProcessInfo... processes)
    {
        return new GpuSnapshot(uuid, "NVIDIA A100", 0, 80000, 0, 0,
                migMode, NOW, List.of(processes));
    }

    private GpuDiscoveryReport report(String runnerId, GpuDiscoveryStatus status,
            Instant discoveredAt, List<GpuSnapshot> gpus)
    {
        return new GpuDiscoveryReport(runnerId, "host", "550.54.15", "550.54.15",
                "host-sig", discoveredAt, status, gpus, null);
    }

    @Test void rejectsReportWhenAuthenticatedRunnerDiffers()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW, List.of(gpu("GPU-aaa", "DISABLED")));
        RunnerProtocolException failure = assertThrows(RunnerProtocolException.class,
                () -> validator.validate("7", body, NOW, Set.of()));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, failure.code());
    }

    @Test void rejectsNonNumericRunnerId()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("not-a-number", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "DISABLED")));
        assertThrows(RunnerProtocolException.class,
                () -> validator.validate("not-a-number", body, NOW, Set.of()));
    }

    @Test void rejectsStaleReport()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW.minusSeconds(31),
                List.of(gpu("GPU-aaa", "DISABLED")));
        RunnerProtocolException failure = assertThrows(RunnerProtocolException.class,
                () -> validator.validate("42", body, NOW, Set.of()));
        assertEquals(RunnerErrorCode.GPU_SNAPSHOT_STALE, failure.code());
    }

    @Test void rejectsFutureReportBeyondTolerance()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW.plusSeconds(61),
                List.of(gpu("GPU-aaa", "DISABLED")));
        assertThrows(RunnerProtocolException.class,
                () -> validator.validate("42", body, NOW, Set.of()));
    }

    @Test void rejectsDuplicateUuids()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-dup", "DISABLED"), gpu("GPU-dup", "DISABLED")));
        RunnerProtocolException failure = assertThrows(RunnerProtocolException.class,
                () -> validator.validate("42", body, NOW, Set.of()));
        assertEquals(RunnerErrorCode.GPU_SNAPSHOT_INVALID, failure.code());
    }

    @Test void migDeviceIsNeverSchedulable()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "ENABLED")));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of()).decisions().get(0);
        assertEquals(DeviceIdentityState.MIG_UNSUPPORTED, decision.identityState());
        assertFalse(decision.schedulable());
    }

    @Test void migUuidIsNeverSchedulable()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("MIG-aaa", "DISABLED")));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of()).decisions().get(0);
        assertEquals(DeviceIdentityState.MIG_UNSUPPORTED, decision.identityState());
        assertFalse(decision.schedulable());
    }

    @Test void migInstanceIdIsNeverSchedulable()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "DISABLED", new GpuProcessInfo(10, "python", 100, "MIG-1"))));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of()).decisions().get(0);
        assertEquals(DeviceIdentityState.MIG_UNSUPPORTED, decision.identityState());
    }

    @Test void externalPidMakesDeviceNotSchedulable()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "DISABLED", new GpuProcessInfo(99, "python", 500, null))));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of(5L)).decisions().get(0);
        assertEquals(DeviceIdentityState.EXTERNAL_OCCUPIED, decision.identityState());
        assertFalse(decision.schedulable());
    }

    @Test void ownedPidDoesNotTriggerExternalOccupancy()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "DISABLED", new GpuProcessInfo(5, "python", 500, null))));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of(5L)).decisions().get(0);
        assertEquals(DeviceIdentityState.OBSERVED, decision.identityState());
        assertTrue(decision.schedulable());
    }

    @Test void cleanGpuIsSchedulable()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.OK, NOW,
                List.of(gpu("GPU-aaa", "DISABLED")));
        GpuDeviceDecision decision = validator.validate("42", body, NOW, Set.of()).decisions().get(0);
        assertEquals(DeviceIdentityState.OBSERVED, decision.identityState());
        assertTrue(decision.schedulable());
    }

    @Test void nonOkReportYieldsNoDecisions()
    {
        GpuSnapshotValidator validator = new GpuSnapshotValidator(FRESH, FUTURE_TOLERANCE);
        GpuDiscoveryReport body = report("42", GpuDiscoveryStatus.UNAVAILABLE, NOW, List.of());
        ValidatedGpuReport validated = validator.validate("42", body, NOW, Set.of());
        assertTrue(validated.decisions().isEmpty());
        assertFalse(validated.observable());
    }
}
