package com.ruoyi.train.runner.process;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.protocol.*;

class ProcessRecoveryTest
{
    @TempDir Path root;
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> ARGS = List.of("-jar", "fixture.jar");

    @Test void matchingLiveProcessRecoversAndStopIsIdempotent() throws Exception
    {
        AtomicInteger destroyed = new AtomicInteger();
        ProcessLookup lookup = pid -> Optional.of(view(pid, STARTED, "/usr/bin/java", ARGS,
                destroyed.get() == 0, destroyed));
        try (var supervisor = new ProcessSupervisor(new ProcessStateStore(root), lookup))
        {
            ManagedProcess saved = saved(RunnerProcessStatus.RUNNING);
            new ProcessStateStore(root).save(saved);
            assertEquals(RunnerProcessStatus.RUNNING, supervisor.recover(saved).status());
            assertEquals(RunnerProcessStatus.STOPPED, supervisor.stop(saved.commandId()).status());
            assertEquals(RunnerProcessStatus.STOPPED, supervisor.stop(saved.commandId()).status());
            assertEquals(1, destroyed.get());
        }
    }

    @Test void pidReuseAndArgumentMismatchNeverDestroy() throws Exception
    {
        for (ProcessLookup.View identity : List.of(
                view(1234L, STARTED.plusSeconds(30), "/usr/bin/java", ARGS, true, new AtomicInteger()),
                view(1234L, STARTED, "/other/java", ARGS, true, new AtomicInteger()),
                view(1234L, STARTED, "/usr/bin/java", List.of("different"), true, new AtomicInteger())))
        {
            AtomicInteger destroys = new AtomicInteger();
            ProcessLookup lookup = pid -> Optional.of(view(pid, identity.startedAt(), identity.executable(),
                    identity.arguments(), true, destroys));
            try (var supervisor = new ProcessSupervisor(new ProcessStateStore(root), lookup))
            {
                ManagedProcess saved = saved(RunnerProcessStatus.RUNNING);
                assertEquals(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH, supervisor.recover(saved).errorCode());
                RunnerProcessSnapshot stopped = supervisor.stop(saved.commandId());
                assertEquals(RunnerProcessStatus.IDENTITY_MISMATCH, stopped.status());
                assertEquals(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH, stopped.errorCode());
                assertEquals(0, destroys.get());
            }
        }
    }

    @Test void missingPidIsUnknownButSavedTerminalStatusRemainsTerminal() throws Exception
    {
        try (var supervisor = new ProcessSupervisor(new ProcessStateStore(root), pid -> Optional.empty()))
        {
            assertEquals(RunnerProcessStatus.UNKNOWN, supervisor.recover(saved(RunnerProcessStatus.RUNNING)).status());
            assertEquals(RunnerProcessStatus.SUCCEEDED, supervisor.recover(saved(RunnerProcessStatus.SUCCEEDED)).status());
        }
    }

    @Test void reconnectsToRealFixtureByPidAndArguments() throws Exception
    {
        List<String> arguments = List.of("-cp", Path.of("target/classes").toAbsolutePath().toString(),
                "com.ruoyi.train.runner.fixture.FixtureMain", "--mode", "sleep", "--seconds", "30");
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(arguments);
        Process child = new ProcessBuilder(command).directory(root.toFile()).start();
        try
        {
            ManagedProcess saved = new ManagedProcess(UUID.randomUUID(), "fixture", child.pid(),
                    child.info().startInstant().orElseThrow(), root.toString(), executable.toString(),
                    ProcessSupervisor.argumentsSha256(arguments), root.resolve("out").toString(),
                    root.resolve("err").toString(), RunnerProcessStatus.RUNNING, null, 1);
            ProcessStateStore store = new ProcessStateStore(root); store.save(saved);
            ProcessLookup lookup = pid -> ProcessLookup.system().find(pid).map(view ->
                    new ProcessLookup.View(view.pid(), view.startedAt(), view.executable(), view.arguments(),
                            view.alive(), () -> {
                                view.destroy().getAsBoolean();
                                try { child.waitFor(); }
                                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                                return true;
                            }, view.destroyForcibly()));
            try (var supervisor = new ProcessSupervisor(store, lookup))
            {
                assertEquals(RunnerProcessStatus.RUNNING, supervisor.recover(store.loadAll().get(0)).status());
                assertEquals(RunnerProcessStatus.STOPPED, supervisor.stop(saved.commandId()).status());
                assertEquals(RunnerProcessStatus.STOPPED, store.loadAll().get(0).status());
            }
        }
        finally { child.destroyForcibly(); child.waitFor(); }
    }

    @Test void pidReusedAfterGracefulStopIsNeverReportedAsOwnedRunningOrDestroyedForcibly() throws Exception
    {
        AtomicInteger graceful = new AtomicInteger();
        AtomicInteger forced = new AtomicInteger();
        ProcessLookup lookup = pid -> graceful.get() == 0
                ? Optional.of(new ProcessLookup.View(pid, STARTED, "/usr/bin/java", ARGS, true,
                        () -> { graceful.incrementAndGet(); return true; }, () -> { forced.incrementAndGet(); return true; }))
                : Optional.of(new ProcessLookup.View(pid, STARTED.plusSeconds(30), "/usr/bin/java", ARGS, true,
                        () -> { throw new AssertionError("must not destroy reused PID"); },
                        () -> { forced.incrementAndGet(); return true; }));
        ProcessStateStore store = new ProcessStateStore(root);
        ManagedProcess saved = saved(RunnerProcessStatus.RUNNING); store.save(saved);
        try (var supervisor = new ProcessSupervisor(store, lookup))
        {
            supervisor.recover(saved);
            RunnerProcessSnapshot after = supervisor.stop(saved.commandId());
            assertEquals(RunnerProcessStatus.IDENTITY_MISMATCH, after.status());
            assertEquals(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH, after.errorCode());
            assertEquals(1, graceful.get()); assertEquals(0, forced.get());
            assertEquals(RunnerProcessStatus.RUNNING, store.loadAll().get(0).status());
        }
    }

    @Test void currentLeaseCanAdoptSameRecoveredProcessWithoutStartingAnother() throws Exception
    {
        ManagedProcess saved = saved(RunnerProcessStatus.RUNNING);
        ProcessStateStore store = new ProcessStateStore(root); store.save(saved);
        ProcessLookup lookup = pid -> Optional.of(view(pid, STARTED, "/usr/bin/java", ARGS, true, new AtomicInteger()));
        ValidatedProcessSpec spec = new ValidatedProcessSpec(root, Path.of("/usr/bin/java"), ARGS,
                Map.of(), 30, "key", root.resolve("out"), root.resolve("err"));
        try (var supervisor = new ProcessSupervisor(store, lookup))
        {
            supervisor.recover(saved);
            RunnerProcessSnapshot adopted = supervisor.start(saved.commandId(), 2, spec);
            assertEquals(1234L, adopted.pid());
            assertEquals(2, adopted.attempt());
            assertEquals(2, store.loadAll().get(0).attempt());
            assertEquals(RunnerProcessStatus.RUNNING, adopted.status());
        }
    }

    private ManagedProcess saved(RunnerProcessStatus status)
    {
        return new ManagedProcess(UUID.randomUUID(), "key", 1234L, STARTED, root.toString(), "/usr/bin/java",
                ProcessSupervisor.argumentsSha256(ARGS), root.resolve("out").toString(), root.resolve("err").toString(),
                status, status == RunnerProcessStatus.SUCCEEDED ? 0 : null, 1);
    }

    private static ProcessLookup.View view(long pid, Instant start, String executable, List<String> args,
            boolean alive, AtomicInteger destroys)
    {
        return new ProcessLookup.View(pid, start, executable, args, alive,
                () -> { destroys.incrementAndGet(); return true; }, () -> false);
    }
}
