package com.ruoyi.train.runner.process;

import java.io.IOException;
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

/** Owns directly spawned Process objects. Durable identity recovery is added separately. */
public final class ProcessSupervisor implements AutoCloseable
{
    @FunctionalInterface interface ProcessLauncher { Process start(ProcessBuilder builder) throws IOException; }
    private final ProcessLauncher launcher;
    public ProcessSupervisor() { this(ProcessBuilder::start); }
    ProcessSupervisor(ProcessLauncher launcher) { this.launcher = Objects.requireNonNull(launcher); }
    private final Map<UUID, Entry> processes = new HashMap<>();
    private final ExecutorService logPumps = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "runner-log-pump"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runner-process-timeouts"); thread.setDaemon(true); return thread;
    });

    public synchronized RunnerProcessSnapshot start(UUID commandId, ValidatedProcessSpec spec) throws IOException
    {
        Objects.requireNonNull(commandId); Objects.requireNonNull(spec);
        if (closed) throw new IllegalStateException("Process supervisor is closed");
        Entry existing = processes.get(commandId);
        if (existing != null)
        {
            if (!sameRequest(existing.spec, spec)) throw new ProcessPolicyException(RunnerErrorCode.PROCESS_ALREADY_STARTED, "Command identity already exists");
            return observe(commandId);
        }
        if (processes.values().stream().anyMatch(e -> e.spec.idempotencyKey().equals(spec.idempotencyKey())))
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
            Entry entry = new Entry(spec, process, process.info().startInstant().orElseGet(Instant::now));
            entry.stdoutPump = pump(process.getInputStream(), logs.stdout);
            entry.stderrPump = pump(process.getErrorStream(), logs.stderr);
            logs.handToPumps();
            processes.put(commandId, entry);
            entry.timeout = timer.schedule(() -> {
                try { stop(commandId); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, spec.timeoutSeconds(), TimeUnit.SECONDS);
            return snapshot(commandId, entry);
        }
    }

    public synchronized RunnerProcessSnapshot observe(UUID commandId)
    {
        Entry entry = require(commandId);
        if (!entry.process.isAlive() && entry.stdoutPump.isDone() && entry.stderrPump.isDone()
                && entry.status == RunnerProcessStatus.RUNNING)
        {
            entry.status = entry.stopRequested ? RunnerProcessStatus.STOPPED
                : entry.process.exitValue() == 0 && pumped(entry.stdoutPump) && pumped(entry.stderrPump)
                    ? RunnerProcessStatus.SUCCEEDED : RunnerProcessStatus.FAILED;
            entry.timeout.cancel(false);
        }
        return snapshot(commandId, entry);
    }

    public synchronized RunnerProcessSnapshot stop(UUID commandId) throws InterruptedException
    {
        RunnerProcessSnapshot current = observe(commandId);
        if (current.status() != RunnerProcessStatus.RUNNING) return current;
        Entry entry = require(commandId);
        entry.stopRequested = true;
        entry.process.destroy();
        if (!entry.process.waitFor(2, TimeUnit.SECONDS))
        {
            entry.process.destroyForcibly();
            if (!entry.process.waitFor(2, TimeUnit.SECONDS)) return snapshot(commandId, entry);
        }
        entry.timeout.cancel(false);
        awaitPump(entry.stdoutPump); awaitPump(entry.stderrPump);
        return observe(commandId);
    }

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
            argumentsSha256(e.spec.arguments()), e.status, e.process.isAlive() ? null : e.process.exitValue(), null);
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
        final ValidatedProcessSpec spec; final Process process; final Instant startedAt;
        RunnerProcessStatus status = RunnerProcessStatus.RUNNING;
        ScheduledFuture<?> timeout;
        Future<Boolean> stdoutPump;
        Future<Boolean> stderrPump;
        boolean stopRequested;
        Entry(ValidatedProcessSpec spec, Process process, Instant startedAt) { this.spec = spec; this.process = process; this.startedAt = startedAt; }
    }
}
