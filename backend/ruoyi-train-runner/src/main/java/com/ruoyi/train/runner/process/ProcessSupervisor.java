package com.ruoyi.train.runner.process;

import java.io.IOException;
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
    private final Map<UUID, Entry> processes = new HashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runner-process-timeouts"); thread.setDaemon(true); return thread;
    });

    public synchronized RunnerProcessSnapshot start(UUID commandId, ValidatedProcessSpec spec) throws IOException
    {
        Objects.requireNonNull(commandId); Objects.requireNonNull(spec);
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
        reserveLog(spec.stdoutPath()); reserveLog(spec.stderrPath());
        List<String> command = new ArrayList<>(); command.add(spec.executable().toString()); command.addAll(spec.arguments());
        ProcessBuilder builder = new ProcessBuilder(command).directory(spec.workingDirectory().toFile());
        builder.environment().clear(); builder.environment().putAll(spec.environment());
        builder.redirectOutput(spec.stdoutPath().toFile()); builder.redirectError(spec.stderrPath().toFile());
        Process process = builder.start();
        Entry entry = new Entry(spec, process, process.info().startInstant().orElseGet(Instant::now));
        processes.put(commandId, entry);
        entry.timeout = timer.schedule(() -> {
            try { stop(commandId); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, spec.timeoutSeconds(), TimeUnit.SECONDS);
        return snapshot(commandId, entry);
    }

    public synchronized RunnerProcessSnapshot observe(UUID commandId)
    {
        Entry entry = require(commandId);
        if (!entry.process.isAlive() && entry.status == RunnerProcessStatus.RUNNING)
        {
            entry.status = entry.process.exitValue() == 0 ? RunnerProcessStatus.SUCCEEDED : RunnerProcessStatus.FAILED;
            entry.timeout.cancel(false);
        }
        return snapshot(commandId, entry);
    }

    public synchronized RunnerProcessSnapshot stop(UUID commandId) throws InterruptedException
    {
        RunnerProcessSnapshot current = observe(commandId);
        if (current.status() != RunnerProcessStatus.RUNNING) return current;
        Entry entry = require(commandId);
        entry.process.destroy();
        if (!entry.process.waitFor(2, TimeUnit.SECONDS))
        {
            entry.process.destroyForcibly();
            if (!entry.process.waitFor(2, TimeUnit.SECONDS)) return snapshot(commandId, entry);
        }
        entry.status = RunnerProcessStatus.STOPPED;
        entry.timeout.cancel(false);
        return snapshot(commandId, entry);
    }

    private static void reserveLog(Path path) throws IOException
    {
        if (!path.getParent().equals(path.getParent().toRealPath()))
            throw new ProcessPolicyException(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE, "Log directory changed");
        Files.createFile(path); // CREATE_NEW rejects pre-existing files and symlinks.
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
    @Override public void close() { timer.shutdownNow(); }
    private static final class Entry
    {
        final ValidatedProcessSpec spec; final Process process; final Instant startedAt;
        RunnerProcessStatus status = RunnerProcessStatus.RUNNING;
        ScheduledFuture<?> timeout;
        Entry(ValidatedProcessSpec spec, Process process, Instant startedAt) { this.spec = spec; this.process = process; this.startedAt = startedAt; }
    }
}
