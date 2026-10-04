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
            RunnerCommandRecord command = commands.findByUuid(snapshot.commandId().toString());
            if (command == null || command.runnerId() != owner
                    || !RunnerCommandType.START_PROCESS.name().equals(command.commandType())) throw notFound();
            RunnerCommandStatus commandStatus = RunnerCommandStatus.valueOf(command.status());
            if (commandStatus == RunnerCommandStatus.QUEUED || commandStatus == RunnerCommandStatus.REJECTED)
                throw conflict();
            if (commandStatus.isTerminal() && terminal(snapshot.status().name())
                    && !commandStatus.name().equals(snapshot.status().name())) throw conflict();
            RunnerProcessRecord previous = processes.findByCommandId(command.commandId());
            if (previous != null && (previous.runnerId() != owner || previous.pid() != snapshot.pid()
                    || !previous.processStartedAt().equals(snapshot.processStartedAt())
                    || !previous.executable().equals(snapshot.executable())
                    || !previous.argumentsSha256().equals(snapshot.argumentsSha256())))
                throw new RunnerProtocolException(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH,
                        "Process identity differs from prior observation");
            if (previous == null)
            {
                RunnerProcessRecord row = new RunnerProcessRecord(null, command.commandId(), owner,
                        snapshot.pid(), snapshot.processStartedAt(), snapshot.executable(), snapshot.argumentsSha256(),
                        snapshot.status().name(), snapshot.exitCode(), code(snapshot), now, now, now);
                try { if (processes.insert(row) != 1) throw conflict(); }
                catch (DuplicateKeyException duplicate)
                {
                    previous = processes.findByCommandId(command.commandId());
                    if (previous == null) throw duplicate;
                    if (previous.runnerId() != owner || previous.pid() != snapshot.pid()
                            || !previous.processStartedAt().equals(snapshot.processStartedAt())
                            || !previous.executable().equals(snapshot.executable())
                            || !previous.argumentsSha256().equals(snapshot.argumentsSha256()))
                        throw new RunnerProtocolException(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH,
                                "Process identity differs from prior observation");
                }
            }
            if (previous != null && !terminal(previous.status()) && !regression(previous.status(), snapshot.status()))
            {
                if (processes.updateSnapshot(command.commandId(), snapshot.status().name(), snapshot.exitCode(),
                        code(snapshot), now) != 1) throw conflict();
            }
            if (previous != null && terminal(previous.status()) && terminal(snapshot.status().name())
                    && !previous.status().equals(snapshot.status().name())) throw conflict();
            if (snapshot.status() == RunnerProcessStatus.UNKNOWN
                    || snapshot.status() == RunnerProcessStatus.IDENTITY_MISMATCH) continue;
            if (commandStatus == RunnerCommandStatus.LEASED)
            {
                advance(owner, command.commandId(), RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING, now);
                commandStatus = RunnerCommandStatus.RUNNING;
            }
            if (commandStatus == RunnerCommandStatus.RUNNING && snapshot.status() != RunnerProcessStatus.RUNNING)
                advance(owner, command.commandId(), RunnerCommandStatus.RUNNING,
                        RunnerCommandStatus.valueOf(snapshot.status().name()), now);
        }
    }

    private void advance(long runnerId, long commandId, RunnerCommandStatus from, RunnerCommandStatus to, Instant now)
    {
        try { from.requireTransitionTo(to); }
        catch (IllegalStateException invalid) { throw conflict(); }
        if (commands.advanceReconciled(runnerId, commandId, from.name(), to.name(), now) != 1)
        {
            RunnerCommandRecord latest = commands.findById(commandId);
            if (latest == null || latest.runnerId() != runnerId
                    || !(latest.status().equals(to.name())
                    || to == RunnerCommandStatus.RUNNING && RunnerCommandStatus.valueOf(latest.status()).isTerminal()))
                throw conflict();
        }
    }

    private static boolean terminal(String status)
    { return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "STOPPED".equals(status); }
    private static boolean regression(String old, RunnerProcessStatus next)
    { return !"UNKNOWN".equals(old) && next == RunnerProcessStatus.UNKNOWN; }
    private static String code(RunnerProcessSnapshot snapshot)
    { return snapshot.errorCode() == null ? null : snapshot.errorCode().name(); }
    private static RunnerProtocolException notFound()
    { return new RunnerProtocolException(RunnerErrorCode.COMMAND_NOT_FOUND, "Command not found for Runner"); }
    private static RunnerProtocolException conflict()
    { return new RunnerProtocolException(RunnerErrorCode.COMMAND_STATUS_CONFLICT, "Reconciliation status conflict"); }
}
