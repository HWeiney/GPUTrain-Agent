package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.train.core.runner.domain.*;
import com.ruoyi.train.core.runner.mapper.*;
import com.ruoyi.train.runner.protocol.*;

class RunnerReconciliationServiceTest
{
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicReference<String> commandStatus = new AtomicReference<>("LEASED");
    private final AtomicReference<RunnerProcessRecord> process = new AtomicReference<>();
    private final AtomicInteger inserts = new AtomicInteger();
    private final AtomicInteger currentAttempt = new AtomicInteger(1);
    private volatile CountDownLatch staleUpdateEntered;
    private volatile CountDownLatch releaseStaleUpdate;
    private boolean duplicateInsert;
    private boolean unchangedUpdateReturnsZero;

    @Test void upsertsByCommandIdAndAdvancesOnlyLegalCommandTransitions()
    {
        var service = service();
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        assertEquals(1, inserts.get());
        assertEquals("RUNNING", commandStatus.get());
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.SUCCEEDED)));
        assertEquals("SUCCEEDED", commandStatus.get());
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        assertEquals("SUCCEEDED", commandStatus.get());
        assertEquals("SUCCEEDED", process.get().status());
    }

    @Test void rejectsOtherRunnerAndChangedProcessIdentity()
    {
        var service = service();
        assertEquals(RunnerErrorCode.COMMAND_NOT_FOUND,
                assertThrows(RunnerProtocolException.class, () -> service.reconcile("8", List.of(snapshot(RunnerProcessStatus.RUNNING)))).code());
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        RunnerProcessSnapshot changed = new RunnerProcessSnapshot(ID, 999, NOW, "/usr/bin/java", "digest",
                RunnerProcessStatus.RUNNING, null, null, 1);
        assertEquals(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH,
                assertThrows(RunnerProtocolException.class, () -> service.reconcile("7", List.of(changed))).code());
        assertEquals(1234, process.get().pid());
    }

    @Test void concurrentDuplicateInsertReReadsCommittedWinner()
    {
        duplicateInsert = true;
        service().reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        assertEquals(1, inserts.get());
        assertEquals("RUNNING", commandStatus.get());
    }

    @Test void conflictingTerminalResultCannotRewriteProcessOrCommand()
    {
        var service = service();
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.SUCCEEDED)));
        RunnerProcessSnapshot failed = new RunnerProcessSnapshot(ID, 1234L, NOW, "/usr/bin/java", "digest",
                RunnerProcessStatus.FAILED, 17, null, 1);
        assertEquals(RunnerErrorCode.COMMAND_STATUS_CONFLICT,
                assertThrows(RunnerProtocolException.class, () -> service.reconcile("7", List.of(failed))).code());
        assertEquals("SUCCEEDED", process.get().status());
        assertEquals("SUCCEEDED", commandStatus.get());
    }

    @Test void staleAttemptCannotWriteProcessOrCommandButCurrentAttemptIsIdempotent()
    {
        currentAttempt.set(2);
        var service = service();
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED,
                assertThrows(RunnerProtocolException.class,
                        () -> service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)))).code());
        assertNull(process.get());
        assertEquals("LEASED", commandStatus.get());
        RunnerProcessSnapshot current = new RunnerProcessSnapshot(ID, 1234L, NOW, "/usr/bin/java", "digest",
                RunnerProcessStatus.RUNNING, null, null, 2);
        service.reconcile("7", List.of(current));
        service.reconcile("7", List.of(current));
        assertEquals("RUNNING", commandStatus.get());
        assertEquals(1, inserts.get());
    }

    @Test void missingRecoveredProcessAdvancesProcessFactToUnknownWithoutCompletingCommand()
    {
        var service = service();
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.UNKNOWN)));
        assertEquals("UNKNOWN", process.get().status());
        assertEquals("RUNNING", commandStatus.get());
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        assertEquals("UNKNOWN", process.get().status());
    }

    @Test void duplicateRunningSnapshotRemainsIdempotentWhenDatabaseReportsZeroChangedRows()
    {
        var service = service();
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        unchangedUpdateReturnsZero = true;
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        assertEquals("RUNNING", process.get().status());
        assertEquals(1, inserts.get());
    }

    @Test void staleRunningWriterCannotOverwriteConcurrentTerminalObservation() throws Exception
    {
        var service = service();
        service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING)));
        staleUpdateEntered = new CountDownLatch(1);
        releaseStaleUpdate = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            Future<?> stale = worker.submit(() -> service.reconcile("7", List.of(snapshot(RunnerProcessStatus.RUNNING))));
            assertTrue(staleUpdateEntered.await(2, TimeUnit.SECONDS));
            service.reconcile("7", List.of(snapshot(RunnerProcessStatus.SUCCEEDED)));
            releaseStaleUpdate.countDown();
            stale.get(2, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", process.get().status());
            assertEquals("SUCCEEDED", commandStatus.get());
        }
        finally { releaseStaleUpdate.countDown(); worker.shutdownNow(); }
    }

    private RunnerReconciliationService service()
    {
        RunnerCommandMapper commands = (RunnerCommandMapper) Proxy.newProxyInstance(
                RunnerCommandMapper.class.getClassLoader(), new Class<?>[] { RunnerCommandMapper.class },
                (proxy, method, args) -> switch (method.getName())
                {
                    case "findByUuid", "findByUuidForUpdate", "findById" -> new RunnerCommandRecord(11L, ID.toString(), 7L, "START_PROCESS", "{}",
                            "key", commandStatus.get(), NOW.plusSeconds(30), currentAttempt.get(), null, null, NOW, NOW);
                    case "advanceReconciled" -> {
                        if ((int) args[2] != currentAttempt.get()
                                || !commandStatus.compareAndSet((String) args[3], (String) args[4])) yield 0;
                        yield 1;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        RunnerProcessMapper processes = (RunnerProcessMapper) Proxy.newProxyInstance(
                RunnerProcessMapper.class.getClassLoader(), new Class<?>[] { RunnerProcessMapper.class },
                (proxy, method, args) -> switch (method.getName())
                {
                    case "findByCommandId" -> process.get();
                    case "insert" -> {
                        process.set((RunnerProcessRecord) args[0]); inserts.incrementAndGet();
                        if (duplicateInsert) throw new DuplicateKeyException("simulated concurrent insert");
                        yield 1;
                    }
                    case "updateSnapshot" -> {
                        if (staleUpdateEntered != null && "RUNNING".equals(args[3]))
                        {
                            staleUpdateEntered.countDown();
                            if (!releaseStaleUpdate.await(2, TimeUnit.SECONDS)) throw new AssertionError("stale update not released");
                        }
                        RunnerProcessRecord old = process.get();
                        if (!old.status().equals(args[2]) || old.runnerId() != (long) args[1]) yield 0;
                        if (unchangedUpdateReturnsZero && old.status().equals(args[3])
                                && Objects.equals(old.exitCode(), args[4])
                                && Objects.equals(old.errorCode(), args[5])) yield 0;
                        process.set(new RunnerProcessRecord(old.processId(), old.commandId(), old.runnerId(), old.pid(),
                                old.processStartedAt(), old.executable(), old.argumentsSha256(), (String) args[3],
                                (Integer) args[4], (String) args[5], (Instant) args[6], old.createdAt(), (Instant) args[6]));
                        yield 1;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        return new RunnerReconciliationService(commands, processes);
    }

    private static RunnerProcessSnapshot snapshot(RunnerProcessStatus status)
    { return new RunnerProcessSnapshot(ID, 1234L, NOW, "/usr/bin/java", "digest", status,
            status == RunnerProcessStatus.SUCCEEDED ? 0 : null, null, 1); }
}
