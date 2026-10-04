package com.ruoyi.train.core.runner.service;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.runner.protocol.ProcessSpec;
import com.ruoyi.train.runner.protocol.RunnerCommandLease;
import com.ruoyi.train.runner.protocol.RunnerCommandReport;
import com.ruoyi.train.runner.protocol.RunnerCommandStatus;
import com.ruoyi.train.runner.protocol.RunnerCommandType;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Service
public class RunnerCommandService
{
    private final RunnerCommandMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    public RunnerCommandService(RunnerCommandMapper mapper, ObjectMapper json)
    { this(mapper, json, Clock.systemUTC()); }

    RunnerCommandService(RunnerCommandMapper mapper, ObjectMapper json, Clock clock)
    {
        this.mapper = Objects.requireNonNull(mapper);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RunnerCommandRecord createFixtureCommand(String runnerId, ProcessSpec spec, long creatorUserId)
    {
        Objects.requireNonNull(spec, "spec");
        if (creatorUserId <= 0) throw new IllegalArgumentException("Creator user ID must be positive");
        if (spec.idempotencyKey().length() > 128)
            throw new IllegalArgumentException("Command idempotency key is too long");
        if (spec.idempotencyKey().regionMatches(true, 0, "stop:", 0, 5))
            throw new IllegalArgumentException("Command idempotency key uses reserved stop namespace");
        long id = numericRunnerId(runnerId);
        RunnerCommandRecord existing = mapper.findByRunnerAndIdempotencyKey(id, spec.idempotencyKey());
        if (existing != null) return sameStart(existing, spec);
        RunnerCommandRecord command = queued(id, RunnerCommandType.START_PROCESS,
                writeJson(spec), spec.idempotencyKey());
        try
        {
            if (mapper.insert(command) != 1) throw new IllegalStateException("Unable to create command");
        }
        catch (DuplicateKeyException duplicate)
        {
            RunnerCommandRecord winner = mapper.findByRunnerAndIdempotencyKey(id, spec.idempotencyKey());
            if (winner == null) throw duplicate;
            return sameStart(winner, spec);
        }
        return Objects.requireNonNull(mapper.findByRunnerAndIdempotencyKey(id, spec.idempotencyKey()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<RunnerCommandLease> leaseNext(String runnerId, Instant now, Duration leaseDuration)
    {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative())
            throw new IllegalArgumentException("Lease duration must be positive");
        long id = numericRunnerId(runnerId);
        RunnerCommandRecord command = mapper.leaseNext(id, now, now.plus(leaseDuration));
        return Optional.ofNullable(command).map(this::toLease);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RunnerCommandRecord report(String runnerId, UUID commandId, RunnerCommandReport report)
    {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(report, "report");
        RunnerCommandRecord current = owned(runnerId, commandId);
        requireAttempt(current, report.attempt());
        if (current.status().equals(report.status().name())) return current;
        RunnerCommandStatus persisted = RunnerCommandStatus.valueOf(current.status());
        if (persisted != report.previousStatus())
            throw statusConflict();
        try { persisted.requireTransitionTo(report.status()); }
        catch (IllegalStateException invalidTransition) { throw statusConflict(); }
        Instant now = clock.instant();
        if (persisted == RunnerCommandStatus.LEASED
                && (current.leaseUntil() == null || current.leaseUntil().isBefore(now)))
            throw new RunnerProtocolException(RunnerErrorCode.COMMAND_LEASE_EXPIRED,
                    "Command lease expired before Runner report");
        int updated = switch (report.status())
        {
            case RUNNING -> mapper.markRunning(current.runnerId(), current.commandId(), report.attempt(),
                    now, current.leaseUntil());
            case REJECTED -> mapper.markRejected(current.runnerId(), current.commandId(), report.attempt(),
                    now, writeJson(report),
                    report.errorCode() == null ? null : report.errorCode().name(), current.leaseUntil());
            case SUCCEEDED, FAILED, STOPPED -> mapper.complete(current.runnerId(), current.commandId(),
                    report.attempt(), report.status().name(), writeJson(report),
                    report.errorCode() == null ? null : report.errorCode().name(), now);
            default -> throw statusConflict();
        };
        if (updated != 1)
        {
            RunnerCommandRecord latest = owned(runnerId, commandId);
            requireAttempt(latest, report.attempt());
            if (latest.status().equals(report.status().name())) return latest;
            throw statusConflict();
        }
        return Objects.requireNonNull(mapper.findById(current.commandId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RunnerCommandRecord requestStop(String runnerId, UUID commandId)
    {
        Objects.requireNonNull(commandId, "commandId");
        RunnerCommandRecord target = owned(runnerId, commandId);
        if (!RunnerCommandType.START_PROCESS.name().equals(target.commandType()))
            throw new IllegalArgumentException("Only START_PROCESS commands can be stopped");
        String key = "stop:" + commandId;
        RunnerCommandRecord existing = mapper.findByRunnerAndIdempotencyKey(target.runnerId(), key);
        if (existing != null) return sameStop(existing, commandId);
        RunnerCommandRecord stop = queued(target.runnerId(), RunnerCommandType.STOP_PROCESS,
                "{\"targetCommandId\":\"" + commandId + "\"}", key);
        try
        {
            if (mapper.insert(stop) != 1) throw new IllegalStateException("Unable to create stop command");
        }
        catch (DuplicateKeyException duplicate)
        {
            RunnerCommandRecord winner = mapper.findByRunnerAndIdempotencyKey(target.runnerId(), key);
            if (winner == null) throw duplicate;
            return sameStop(winner, commandId);
        }
        return Objects.requireNonNull(mapper.findByRunnerAndIdempotencyKey(target.runnerId(), key));
    }

    private RunnerCommandRecord owned(String runnerId, UUID commandId)
    {
        long id;
        try { id = numericRunnerId(runnerId); }
        catch (IllegalArgumentException invalidId) { throw commandNotFound(); }
        RunnerCommandRecord command = mapper.findByUuid(commandId.toString());
        if (command == null || command.runnerId() != id)
            throw commandNotFound();
        return command;
    }

    private static void requireAttempt(RunnerCommandRecord command, int attempt)
    {
        if (command.attemptCount() != attempt)
            throw new RunnerProtocolException(RunnerErrorCode.COMMAND_LEASE_EXPIRED,
                    "Command lease generation is no longer current");
    }

    private static RunnerProtocolException commandNotFound()
    {
        return new RunnerProtocolException(RunnerErrorCode.COMMAND_NOT_FOUND,
                "Command not found for Runner");
    }

    private static RunnerProtocolException statusConflict()
    {
        return new RunnerProtocolException(RunnerErrorCode.COMMAND_STATUS_CONFLICT,
                "Command status conflicts with report");
    }

    private RunnerCommandRecord sameStart(RunnerCommandRecord existing, ProcessSpec spec)
    {
        if (!RunnerCommandType.START_PROCESS.name().equals(existing.commandType())
                || !spec.idempotencyKey().equals(existing.idempotencyKey())
                || !spec.equals(readJson(existing.payload(), ProcessSpec.class)))
            throw new IllegalArgumentException("Command idempotency key is bound to different fields");
        return existing;
    }

    private RunnerCommandRecord sameStop(RunnerCommandRecord existing, UUID targetId)
    {
        if (!RunnerCommandType.STOP_PROCESS.name().equals(existing.commandType())
                || !targetId.toString().equals(readTree(existing.payload()).path("targetCommandId").asText()))
            throw new IllegalArgumentException("Stop idempotency key is bound to another target");
        return existing;
    }

    private RunnerCommandLease toLease(RunnerCommandRecord command)
    {
        RunnerCommandType type = RunnerCommandType.valueOf(command.commandType());
        return type == RunnerCommandType.START_PROCESS
                ? new RunnerCommandLease(UUID.fromString(command.commandUuid()), type,
                        readJson(command.payload(), ProcessSpec.class), null, command.leaseUntil(),
                        command.attemptCount())
                : new RunnerCommandLease(UUID.fromString(command.commandUuid()), type, null,
                        UUID.fromString(readTree(command.payload()).path("targetCommandId").asText()),
                        command.leaseUntil(), command.attemptCount());
    }

    private static RunnerCommandRecord queued(long runnerId, RunnerCommandType type, String payload, String key)
    {
        Instant now = Instant.now();
        return new RunnerCommandRecord(null, UUID.randomUUID().toString(), runnerId, type.name(), payload,
                key, RunnerCommandStatus.QUEUED.name(), null, 0, null, null, now, now);
    }

    private static long numericRunnerId(String runnerId)
    {
        try
        {
            long id = Long.parseLong(runnerId);
            if (id > 0) return id;
        }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid Runner ID");
    }

    private String writeJson(Object value)
    {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid command payload", failure); }
    }

    private <T> T readJson(String value, Class<T> type)
    {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid persisted command payload", failure); }
    }

    private JsonNode readTree(String value)
    {
        try { return json.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid persisted command payload", failure); }
    }
}
