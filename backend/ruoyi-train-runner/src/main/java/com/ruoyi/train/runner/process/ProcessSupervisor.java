package com.ruoyi.train.runner.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.train.runner.protocol.*;

/** Starts constrained processes and validates durable identity before recovered operations. */
public final class ProcessSupervisor implements AutoCloseable
{
    @FunctionalInterface interface ProcessLauncher { Process start(ProcessBuilder builder) throws IOException; }
    private final ProcessLauncher launcher;
    private final ProcessStateStore stateStore;
    private final ProcessLookup processLookup;
    public ProcessSupervisor() { this(ProcessBuilder::start, null, ProcessLookup.system()); }
    public ProcessSupervisor(ProcessStateStore stateStore, ProcessLookup processLookup)
    { this(ProcessBuilder::start, Objects.requireNonNull(stateStore), Objects.requireNonNull(processLookup)); }
    ProcessSupervisor(ProcessLauncher launcher) { this(launcher, null, ProcessLookup.system()); }
    private ProcessSupervisor(ProcessLauncher launcher, ProcessStateStore stateStore, ProcessLookup processLookup)
    { this.launcher = Objects.requireNonNull(launcher); this.stateStore = stateStore; this.processLookup = processLookup; }
    private final Map<UUID, Entry> processes = new HashMap<>();
    private final Map<UUID, ManagedProcess> recovered = new HashMap<>();
    private final ExecutorService logPumps = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "runner-log-pump"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runner-process-timeouts"); thread.setDaemon(true); return thread;
    });

    public synchronized RunnerProcessSnapshot start(UUID commandId, ValidatedProcessSpec spec) throws IOException
    { return start(commandId, 1, spec); }

    public synchronized RunnerProcessSnapshot start(UUID commandId, int attempt, ValidatedProcessSpec spec) throws IOException
    {
        Objects.requireNonNull(commandId); Objects.requireNonNull(spec);
        if (attempt <= 0) throw new IllegalArgumentException("Process lease attempt must be positive");
        if (closed) throw new IllegalStateException("Process supervisor is closed");
        Entry existing = processes.get(commandId);
        if (existing != null)
        {
            if (!sameRequest(existing.spec, spec))
                throw new ProcessPolicyException(RunnerErrorCode.PROCESS_ALREADY_STARTED, "Command identity already exists");
            if (attempt < existing.attempt)
                throw new ProcessPolicyException(RunnerErrorCode.COMMAND_LEASE_EXPIRED, "Older process lease attempt");
            if (attempt > existing.attempt)
            {
                RunnerProcessSnapshot current = observe(commandId);
                if (current.status() == RunnerProcessStatus.RUNNING) matchingLive(managed(commandId, existing));
                if (stateStore != null) stateStore.save(managed(commandId, existing).withAttempt(attempt));
                existing.attempt = attempt;
            }
            return observe(commandId);
        }
        ManagedProcess restored = recovered.get(commandId);
        if (restored != null)
        {
            if (!restored.idempotencyKey().equals(spec.idempotencyKey())
                    || !restored.workingDirectory().equals(spec.workingDirectory().toString())
                    || !restored.executable().equals(spec.executable().toString())
                    || !restored.argumentsSha256().equals(argumentsSha256(spec.arguments())))
                throw new ProcessPolicyException(RunnerErrorCode.PROCESS_ALREADY_STARTED, "Command identity already exists");
            ProcessRecovery recovery = recover(restored);
            if (recovery.errorCode() != null)
                throw new ProcessPolicyException(recovery.errorCode(), "Process identity changed");
            if (recovery.status() == RunnerProcessStatus.UNKNOWN)
                throw new ProcessPolicyException(RunnerErrorCode.COMMAND_STATUS_CONFLICT, "Recovered process is no longer observable");
            if (attempt < restored.attempt())
                throw new ProcessPolicyException(RunnerErrorCode.COMMAND_LEASE_EXPIRED, "Older process lease attempt");
            if (attempt > restored.attempt())
            {
                restored = restored.withAttempt(attempt);
                stateStore.save(restored);
                recovered.put(commandId, restored);
            }
            return recoveredSnapshot(restored, recovery);
        }
        if (processes.values().stream().anyMatch(e -> e.spec.idempotencyKey().equals(spec.idempotencyKey())))
            throw new ProcessPolicyException(RunnerErrorCode.PROCESS_ALREADY_STARTED, "Idempotency key already exists");
        if (recovered.values().stream().anyMatch(e -> e.idempotencyKey().equals(spec.idempotencyKey())))
            throw new ProcessPolicyException(RunnerErrorCode.PROCESS_ALREADY_STARTED, "Idempotency key already exists");
        // Recheck canonical paths immediately before creating files and starting the process.
        if (!spec.workingDirectory().equals(spec.workingDirectory().toRealPath()) || !spec.executable().equals(spec.executable().toRealPath()))
            throw new ProcessPolicyException(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE, "Validated path changed");
        List<String> command = new ArrayList<>(); command.add(spec.executable().toString()); command.addAll(spec.arguments());
        ProcessBuilder builder = new ProcessBuilder(command).directory(spec.workingDirectory().toFile());
        builder.environment().clear(); builder.environment().putAll(spec.environment());
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE); builder.redirectError(ProcessBuilder.Redirect.PIPE);
        try (ProcessLogs logs = new ProcessLogs(spec.stdoutPath(), spec.stderrPath()))
        {
            Process process = launcher.start(builder);
            Optional<Instant> actualStart = process.info().startInstant();
            if (stateStore != null && actualStart.isEmpty())
            {
                process.destroyForcibly();
                throw new IOException("Process start identity is unavailable");
            }
            Entry entry = new Entry(spec, process, actualStart.orElseGet(Instant::now), attempt);
            entry.stdoutPump = pump(process.getInputStream(), logs.stdout);
            entry.stderrPump = pump(process.getErrorStream(), logs.stderr);
            logs.handToPumps();
            processes.put(commandId, entry);
            if (stateStore != null)
            {
                try { stateStore.save(managed(commandId, entry)); }
                catch (IOException failure)
                {
                    processes.remove(commandId);
                    process.destroyForcibly();
                    throw failure;
                }
            }
            entry.timeout = timer.schedule(() -> {
                try { stop(commandId); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }, spec.timeoutSeconds(), TimeUnit.SECONDS);
            return snapshot(commandId, entry);
        }
    }

    public synchronized RunnerProcessSnapshot observe(UUID commandId)
    {
        ManagedProcess restored = recovered.get(commandId);
        if (restored != null) return recoveredSnapshot(restored, recover(restored));
        Entry entry = require(commandId);
        if (!entry.process.isAlive() && entry.stdoutPump.isDone() && entry.stderrPump.isDone()
                && entry.status == RunnerProcessStatus.RUNNING)
        {
            entry.status = entry.stopRequested ? RunnerProcessStatus.STOPPED
                : entry.process.exitValue() == 0 && pumped(entry.stdoutPump) && pumped(entry.stderrPump)
                    ? RunnerProcessStatus.SUCCEEDED : RunnerProcessStatus.FAILED;
            entry.timeout.cancel(false);
            if (stateStore != null)
            {
                try { stateStore.markTerminal(commandId, entry.status, entry.process.exitValue()); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        }
        return snapshot(commandId, entry);
    }

    public synchronized RunnerProcessSnapshot stop(UUID commandId) throws InterruptedException, IOException
    {
        ManagedProcess restored = recovered.get(commandId);
        if (restored != null) return stopRecovered(restored);
        RunnerProcessSnapshot current = observe(commandId);
        if (current.status() != RunnerProcessStatus.RUNNING) return current;
        Entry entry = require(commandId);
        ManagedProcess identity = managed(commandId, entry);
        ProcessLookup.View view = matchingLive(identity);
        entry.stopRequested = true;
        view.destroy().getAsBoolean();
        if (!entry.process.waitFor(2, TimeUnit.SECONDS))
        {
            matchingLive(identity).destroyForcibly().getAsBoolean();
            if (!entry.process.waitFor(2, TimeUnit.SECONDS)) return snapshot(commandId, entry);
        }
        entry.timeout.cancel(false);
        awaitPump(entry.stdoutPump); awaitPump(entry.stderrPump);
        return observe(commandId);
    }

    public synchronized ProcessRecovery recover(ManagedProcess saved)
    {
        Objects.requireNonNull(saved);
        recovered.put(saved.commandId(), saved);
        if (terminal(saved.status())) return new ProcessRecovery(saved.status(), null);
        Optional<ProcessLookup.View> current = processLookup.find(saved.pid());
        if (current.isEmpty() || !current.get().alive())
            return new ProcessRecovery(RunnerProcessStatus.UNKNOWN, null);
        if (!sameIdentity(saved, current.get()))
            return new ProcessRecovery(RunnerProcessStatus.IDENTITY_MISMATCH, RunnerErrorCode.PROCESS_IDENTITY_MISMATCH);
        return new ProcessRecovery(RunnerProcessStatus.RUNNING, null);
    }

    private RunnerProcessSnapshot stopRecovered(ManagedProcess saved) throws IOException, InterruptedException
    {
        ProcessRecovery state = recover(saved);
        if (terminal(state.status()) || state.status() == RunnerProcessStatus.UNKNOWN
                || state.status() == RunnerProcessStatus.IDENTITY_MISMATCH)
            return recoveredSnapshot(saved, state);
        ProcessLookup.View current = matchingLive(saved);
        current.destroy().getAsBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && recover(saved).status() == RunnerProcessStatus.RUNNING)
            Thread.sleep(20);
        ProcessRecovery afterGrace = recover(saved);
        if (afterGrace.status() == RunnerProcessStatus.RUNNING)
        {
            matchingLive(saved).destroyForcibly().getAsBoolean();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && recover(saved).status() == RunnerProcessStatus.RUNNING)
                Thread.sleep(20);
        }
        ProcessRecovery finalState = recover(saved);
        if (finalState.status() != RunnerProcessStatus.UNKNOWN)
            return recoveredSnapshot(saved, finalState);
        ManagedProcess stopped = stateStore.markTerminal(saved.commandId(), RunnerProcessStatus.STOPPED, null);
        recovered.put(saved.commandId(), stopped);
        return recoveredSnapshot(stopped, new ProcessRecovery(RunnerProcessStatus.STOPPED, null));
    }

    private ProcessLookup.View matchingLive(ManagedProcess saved)
    {
        ProcessLookup.View view = processLookup.find(saved.pid()).orElse(null);
        if (view == null || !view.alive() || !sameIdentity(saved, view))
            throw new ProcessPolicyException(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH, "Process identity changed");
        return view;
    }

    private static boolean sameIdentity(ManagedProcess saved, ProcessLookup.View view)
    {
        return saved.pid() == view.pid() && saved.processStartedAt().equals(view.startedAt())
                && view.executable() != null
                && Path.of(saved.executable()).toAbsolutePath().normalize()
                        .equals(Path.of(view.executable()).toAbsolutePath().normalize())
                && view.arguments() != null && saved.argumentsSha256().equals(argumentsSha256(view.arguments()));
    }

    private static boolean terminal(RunnerProcessStatus status)
    { return status == RunnerProcessStatus.SUCCEEDED || status == RunnerProcessStatus.FAILED || status == RunnerProcessStatus.STOPPED; }

    private static RunnerProcessSnapshot recoveredSnapshot(ManagedProcess saved, ProcessRecovery recovery)
    { return new RunnerProcessSnapshot(saved.commandId(), saved.pid(), saved.processStartedAt(), saved.executable(),
            saved.argumentsSha256(), recovery.status(), saved.exitCode(), recovery.errorCode(), saved.attempt()); }

    private static ManagedProcess managed(UUID id, Entry entry)
    { return new ManagedProcess(id, entry.spec.idempotencyKey(), entry.process.pid(), entry.startedAt,
            entry.spec.workingDirectory().toString(), entry.spec.executable().toString(),
            argumentsSha256(entry.spec.arguments()), entry.spec.stdoutPath().toString(),
            entry.spec.stderrPath().toString(), entry.status,
            entry.process.isAlive() ? null : entry.process.exitValue(), entry.attempt); }

    private Future<Boolean> pump(InputStream input, OutputStream output)
    {
        return logPumps.submit(() -> {
            try (input; output) { input.transferTo(output); return true; }
            catch (IOException failure) { return false; }
        });
    }
    private static boolean pumped(Future<Boolean> pump)
    {
        try { return pump.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException failure) { return false; }
    }
    private static void awaitPump(Future<Boolean> pump) throws InterruptedException
    {
        try { pump.get(2, TimeUnit.SECONDS); }
        catch (ExecutionException | TimeoutException ignored) { }
    }
    private Entry require(UUID id)
    {
        Entry entry = processes.get(id);
        if (entry == null) throw new ProcessPolicyException(RunnerErrorCode.COMMAND_NOT_FOUND, "Unknown local command");
        return entry;
    }
    private RunnerProcessSnapshot snapshot(UUID id, Entry e)
    {
        return new RunnerProcessSnapshot(id, e.process.pid(), e.startedAt, e.spec.executable().toString(),
            argumentsSha256(e.spec.arguments()), e.status, e.process.isAlive() ? null : e.process.exitValue(), null,
            e.attempt);
    }
    public static String argumentsSha256(List<String> arguments)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String argument : arguments)
            {
                byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean sameRequest(ValidatedProcessSpec a, ValidatedProcessSpec b)
    {
        return a.workingDirectory().equals(b.workingDirectory()) && a.executable().equals(b.executable())
                && a.arguments().equals(b.arguments()) && a.environment().equals(b.environment())
                && a.timeoutSeconds() == b.timeoutSeconds() && a.idempotencyKey().equals(b.idempotencyKey());
    }
    @Override public synchronized void close()
    {
        closed = true; timer.shutdownNow();
        // Existing pumps own their descriptors until EOF; no replacement path is ever opened.
        logPumps.shutdown();
    }
    private static final class Entry
    {
        final ValidatedProcessSpec spec; final Process process; final Instant startedAt; int attempt;
        RunnerProcessStatus status = RunnerProcessStatus.RUNNING;
        ScheduledFuture<?> timeout;
        Future<Boolean> stdoutPump;
        Future<Boolean> stderrPump;
        boolean stopRequested;
        Entry(ValidatedProcessSpec spec, Process process, Instant startedAt, int attempt)
        { this.spec = spec; this.process = process; this.startedAt = startedAt; this.attempt = attempt; }
    }
}
