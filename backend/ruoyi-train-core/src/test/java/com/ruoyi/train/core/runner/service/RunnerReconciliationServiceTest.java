package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
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
    private boolean duplicateInsert;

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
                RunnerProcessStatus.RUNNING, null, null);
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
                RunnerProcessStatus.FAILED, 17, null);
        assertEquals(RunnerErrorCode.COMMAND_STATUS_CONFLICT,
                assertThrows(RunnerProtocolException.class, () -> service.reconcile("7", List.of(failed))).code());
        assertEquals("SUCCEEDED", process.get().status());
        assertEquals("SUCCEEDED", commandStatus.get());
    }

    private RunnerReconciliationService service()
    {
        RunnerCommandMapper commands = (RunnerCommandMapper) Proxy.newProxyInstance(
                RunnerCommandMapper.class.getClassLoader(), new Class<?>[] { RunnerCommandMapper.class },
                (proxy, method, args) -> switch (method.getName())
                {
                    case "findByUuid" -> new RunnerCommandRecord(11L, ID.toString(), 7L, "START_PROCESS", "{}",
                            "key", commandStatus.get(), NOW.plusSeconds(30), 1, null, null, NOW, NOW);
                    case "advanceReconciled" -> {
                        if (!commandStatus.compareAndSet((String) args[2], (String) args[3])) yield 0;
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
                        RunnerProcessRecord old = process.get();
                        process.set(new RunnerProcessRecord(old.processId(), old.commandId(), old.runnerId(), old.pid(),
                                old.processStartedAt(), old.executable(), old.argumentsSha256(), (String) args[1],
                                (Integer) args[2], (String) args[3], (Instant) args[4], old.createdAt(), (Instant) args[4]));
                        yield 1;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        return new RunnerReconciliationService(commands, processes);
    }

    private static RunnerProcessSnapshot snapshot(RunnerProcessStatus status)
    { return new RunnerProcessSnapshot(ID, 1234L, NOW, "/usr/bin/java", "digest", status,
            status == RunnerProcessStatus.SUCCEEDED ? 0 : null, null); }
}
