package com.ruoyi.train.core.runner.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.train.core.runner.domain.*;
import com.ruoyi.train.core.runner.mapper.*;
import com.ruoyi.train.runner.protocol.*;

@Service
public class RunnerReconciliationService
{
    private final RunnerCommandMapper commands;
    private final RunnerProcessMapper processes;

    public RunnerReconciliationService(RunnerCommandMapper commands, RunnerProcessMapper processes)
    { this.commands = Objects.requireNonNull(commands); this.processes = Objects.requireNonNull(processes); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcile(String runnerId, List<RunnerProcessSnapshot> snapshots)
    {
        Objects.requireNonNull(snapshots);
        long owner;
        try { owner = Long.parseLong(runnerId); }
        catch (NumberFormatException invalid) { throw notFound(); }
        if (owner <= 0) throw notFound();
        Instant now = Instant.now();
        for (RunnerProcessSnapshot snapshot : snapshots)
        {
            Objects.requireNonNull(snapshot);
            RunnerCommandRecord command = commands.findByUuidForUpdate(snapshot.commandId().toString());
            if (command == null || command.runnerId() != owner
                    || !RunnerCommandType.START_PROCESS.name().equals(command.commandType())) throw notFound();
            if (command.attemptCount() != snapshot.attempt())
                throw new RunnerProtocolException(RunnerErrorCode.COMMAND_LEASE_EXPIRED,
                        "Process snapshot lease attempt is no longer current");
            RunnerCommandStatus commandStatus = RunnerCommandStatus.valueOf(command.status());
            if (commandStatus == RunnerCommandStatus.QUEUED || commandStatus == RunnerCommandStatus.REJECTED)
                throw conflict();
            if (commandStatus.isTerminal() && terminal(snapshot.status().name())
                    && !commandStatus.name().equals(snapshot.status().name())) throw conflict();
            if (commandStatus.isTerminal() && !commandStatus.name().equals(snapshot.status().name())) continue;
            upsertProcess(command.commandId(), owner, snapshot, now);
            if (snapshot.status() == RunnerProcessStatus.UNKNOWN
                    || snapshot.status() == RunnerProcessStatus.IDENTITY_MISMATCH) continue;
            if (commandStatus == RunnerCommandStatus.LEASED)
            {
                advance(owner, command.commandId(), snapshot.attempt(), RunnerCommandStatus.LEASED,
                        RunnerCommandStatus.RUNNING, now);
                commandStatus = RunnerCommandStatus.RUNNING;
            }
            if (commandStatus == RunnerCommandStatus.RUNNING && snapshot.status() != RunnerProcessStatus.RUNNING)
                advance(owner, command.commandId(), snapshot.attempt(), RunnerCommandStatus.RUNNING,
                        RunnerCommandStatus.valueOf(snapshot.status().name()), now);
        }
    }

    private void upsertProcess(long commandId, long runnerId, RunnerProcessSnapshot snapshot, Instant now)
    {
        RunnerProcessRecord current = processes.findByCommandId(commandId);
        if (current == null)
        {
            RunnerProcessRecord row = new RunnerProcessRecord(null, commandId, runnerId,
                    snapshot.pid(), snapshot.processStartedAt(), snapshot.executable(), snapshot.argumentsSha256(),
                    snapshot.status().name(), snapshot.exitCode(), code(snapshot), now, now, now);
            try { if (processes.insert(row) != 1) throw conflict(); return; }
            catch (DuplicateKeyException duplicate)
            {
                current = processes.findByCommandId(commandId);
                if (current == null) throw duplicate;
            }
        }
        for (int retries = 0; retries < 3; retries++)
        {
            requireSameIdentity(current, runnerId, snapshot);
            if (terminal(current.status()))
            {
                if (terminal(snapshot.status().name()) && !current.status().equals(snapshot.status().name()))
                    throw conflict();
                return;
            }
            if (regression(current.status(), snapshot.status())) return;
            if (processes.updateSnapshot(commandId, runnerId, current.status(), snapshot.status().name(),
                    snapshot.exitCode(), code(snapshot), now) == 1) return;
            current = processes.findByCommandId(commandId);
            if (current == null) throw conflict();
            requireSameIdentity(current, runnerId, snapshot);
            if (current.status().equals(snapshot.status().name())
                    && Objects.equals(current.exitCode(), snapshot.exitCode())
                    && Objects.equals(current.errorCode(), code(snapshot))) return;
        }
        throw conflict();
    }

    private static void requireSameIdentity(RunnerProcessRecord current, long runnerId, RunnerProcessSnapshot snapshot)
    {
        if (current.runnerId() != runnerId || current.pid() != snapshot.pid()
                || !current.processStartedAt().equals(snapshot.processStartedAt())
                || !current.executable().equals(snapshot.executable())
                || !current.argumentsSha256().equals(snapshot.argumentsSha256()))
            throw new RunnerProtocolException(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH,
                    "Process identity differs from prior observation");
    }

    private void advance(long runnerId, long commandId, int attempt,
            RunnerCommandStatus from, RunnerCommandStatus to, Instant now)
    {
        try { from.requireTransitionTo(to); }
        catch (IllegalStateException invalid) { throw conflict(); }
        if (commands.advanceReconciled(runnerId, commandId, attempt, from.name(), to.name(), now) != 1)
        {
            RunnerCommandRecord latest = commands.findById(commandId);
            if (latest == null || latest.runnerId() != runnerId || latest.attemptCount() != attempt
                    || !(latest.status().equals(to.name())
                    || to == RunnerCommandStatus.RUNNING && RunnerCommandStatus.valueOf(latest.status()).isTerminal()))
                throw conflict();
        }
    }

    private static boolean terminal(String status)
    { return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "STOPPED".equals(status); }
    private static boolean regression(String old, RunnerProcessStatus next)
    { return "UNKNOWN".equals(old) && next == RunnerProcessStatus.RUNNING
            || "IDENTITY_MISMATCH".equals(old) && next != RunnerProcessStatus.IDENTITY_MISMATCH; }
    private static String code(RunnerProcessSnapshot snapshot)
    { return snapshot.errorCode() == null ? null : snapshot.errorCode().name(); }
    private static RunnerProtocolException notFound()
    { return new RunnerProtocolException(RunnerErrorCode.COMMAND_NOT_FOUND, "Command not found for Runner"); }
    private static RunnerProtocolException conflict()
    { return new RunnerProtocolException(RunnerErrorCode.COMMAND_STATUS_CONFLICT, "Reconciliation status conflict"); }
}
