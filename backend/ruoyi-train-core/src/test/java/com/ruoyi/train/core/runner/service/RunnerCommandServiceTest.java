package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.runner.protocol.*;

class RunnerCommandServiceTest
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final MemoryCommands commands = new MemoryCommands();
    private final RunnerCommandService service = new RunnerCommandService(commands, new ObjectMapper(),
            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

    @Test void duplicateIdempotencyKeyReturnsOriginalCommand()
    {
        RunnerCommandRecord first = service.createFixtureCommand("1", spec("same-key"), 1L);
        RunnerCommandRecord second = service.createFixtureCommand("1", spec("same-key"), 1L);
        assertEquals(first.commandUuid(), second.commandUuid());
        assertEquals(1, commands.rows.size());
        assertThrows(IllegalArgumentException.class, () -> service.createFixtureCommand("1",
                new ProcessSpec("/other", "/bin/true", List.of(), Map.of(), 30, "same-key"), 1L));
    }

    @Test void expiredLeaseCanBeRetriedButRunningCommandCannot()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("lease"), 1L);
        var first = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow();
        assertEquals(UUID.fromString(command.commandUuid()), first.commandId());
        assertTrue(service.leaseNext("1", NOW.plusSeconds(20), Duration.ofSeconds(30)).isEmpty());
        var second = service.leaseNext("1", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
        assertEquals(first.commandId(), second.commandId());
        assertEquals(first.attempt() + 1, second.attempt());
        service.report("1", first.commandId(), report(second.attempt(), RunnerCommandStatus.LEASED,
                RunnerCommandStatus.RUNNING));
        assertTrue(service.leaseNext("1", NOW.plusSeconds(100), Duration.ofSeconds(30)).isEmpty());
    }

    @Test void finalStateIsImmutableAndDuplicateReportSucceeds()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("finish"), 1L);
        UUID id = UUID.fromString(command.commandUuid());
        int attempt = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow().attempt();
        service.report("1", id, report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING));
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(attempt + 1, RunnerCommandStatus.RUNNING, RunnerCommandStatus.SUCCEEDED))).code());
        RunnerCommandReport success = report(attempt, RunnerCommandStatus.RUNNING, RunnerCommandStatus.SUCCEEDED);
        assertEquals("SUCCEEDED", service.report("1", id, success).status());
        assertEquals("SUCCEEDED", service.report("1", id, success).status());
        assertEquals(RunnerErrorCode.COMMAND_STATUS_CONFLICT, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(attempt, RunnerCommandStatus.RUNNING, RunnerCommandStatus.FAILED))).code());
        assertEquals(RunnerErrorCode.COMMAND_STATUS_CONFLICT, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING))).code());
    }

    @Test void wrongRunnerCannotReportOrStopAndStopIsIdempotent()
    {
        RunnerCommandRecord start = service.createFixtureCommand("1", spec("stop"), 1L);
        UUID id = UUID.fromString(start.commandUuid());
        assertEquals(RunnerErrorCode.COMMAND_NOT_FOUND, assertThrows(RunnerProtocolException.class,
                () -> service.report("2", id,
                        report(1, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING))).code());
        assertEquals(RunnerErrorCode.COMMAND_NOT_FOUND,
                assertThrows(RunnerProtocolException.class, () -> service.requestStop("2", id)).code());
        RunnerCommandRecord stop = service.requestStop("1", id);
        assertEquals(RunnerCommandType.STOP_PROCESS.name(), stop.commandType());
        assertEquals(stop.commandUuid(), service.requestStop("1", id).commandUuid());
        assertEquals(2, commands.rows.size());
        assertNotEquals(stop.commandUuid(), start.commandUuid());
    }

    @Test void expiredLeaseCannotBecomeRunning()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("expired"), 1L);
        int attempt = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow().attempt();
        RunnerCommandService lateService = new RunnerCommandService(commands, new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        RunnerProtocolException error = assertThrows(RunnerProtocolException.class,
                () -> lateService.report("1", UUID.fromString(command.commandUuid()),
                        report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)));
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, error.code());
    }

    @Test void fixtureCannotUseStopIdempotencyNamespace()
    {
        assertThrows(IllegalArgumentException.class,
                () -> service.createFixtureCommand("1", spec("stop:reserved"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createFixtureCommand("1", spec("Stop:" + UUID.randomUUID()), 1L));
    }

    @Test void staleAttemptCannotReportAfterNewLeaseEvenBeforeNewRunnerReports()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("fenced"), 1L);
        var first = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow();
        var second = service.leaseNext("1", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
        UUID id = UUID.fromString(command.commandUuid());
        RunnerProtocolException stale = assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(first.attempt(), RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)));
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, stale.code());
        assertEquals("LEASED", commands.findByUuid(command.commandUuid()).status());
        assertEquals("RUNNING", service.report("1", id,
                report(second.attempt(), RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)).status());
    }

    @Test void caseVariantIdempotencyKeyIsNotTreatedAsDuplicate()
    {
        service.createFixtureCommand("1", spec("Key"), 1L);
        commands.caseInsensitiveLookup = true;
        assertThrows(IllegalArgumentException.class,
                () -> service.createFixtureCommand("1", spec("key"), 1L));
        commands.caseInsensitiveLookup = false;
        service.createFixtureCommand("1", spec("key"), 1L);
        assertEquals(2, commands.rows.size());
    }

    @Test void rejectedReportAlsoRequiresCurrentAttempt()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("rejected"), 1L);
        int attempt = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow().attempt();
        UUID id = UUID.fromString(command.commandUuid());
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(attempt + 1, RunnerCommandStatus.LEASED, RunnerCommandStatus.REJECTED))).code());
        assertEquals("REJECTED", service.report("1", id,
                report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.REJECTED)).status());
    }

    @Test void invalidTransitionAndUnknownCommandUseStableErrors()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("invalid-transition"), 1L);
        int attempt = service.leaseNext("1", NOW, Duration.ofSeconds(30)).orElseThrow().attempt();
        UUID id = UUID.fromString(command.commandUuid());
        assertEquals(RunnerErrorCode.COMMAND_STATUS_CONFLICT, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.SUCCEEDED))).code());
        assertEquals(RunnerErrorCode.COMMAND_NOT_FOUND, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", UUID.randomUUID(),
                        report(attempt, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING))).code());
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, assertThrows(RunnerProtocolException.class,
                () -> service.report("1", id,
                        report(0, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING))).code());
    }

    private static ProcessSpec spec(String key)
    { return new ProcessSpec("/tmp", "/bin/true", List.of(), Map.of(), 30, key); }
    private static RunnerCommandReport report(int attempt, RunnerCommandStatus previous, RunnerCommandStatus status)
    { return new RunnerCommandReport(previous, status, null, null, null, null, null, attempt); }

    private static final class MemoryCommands implements RunnerCommandMapper
    {
        private final Map<Long, RunnerCommandRecord> rows = new HashMap<>();
        private boolean caseInsensitiveLookup;
        private long nextId = 1;
        @Override public int insert(RunnerCommandRecord command)
        { rows.put(nextId, with(command, nextId++, command.status(), command.leaseUntil())); return 1; }
        @Override public RunnerCommandRecord findById(Long id) { return rows.get(id); }
        @Override public RunnerCommandRecord findByUuid(String uuid)
        { return rows.values().stream().filter(c -> c.commandUuid().equals(uuid)).findFirst().orElse(null); }
        @Override public RunnerCommandRecord findByUuidForUpdate(String uuid)
        { return findByUuid(uuid); }
        @Override public RunnerCommandRecord findByRunnerAndIdempotencyKey(Long runner, String key)
        { return rows.values().stream().filter(c -> c.runnerId().equals(runner)
                && (caseInsensitiveLookup ? c.idempotencyKey().equalsIgnoreCase(key)
                    : c.idempotencyKey().equals(key)))
                .findFirst().orElse(null); }
        @Override public Long selectLeaseCandidateForUpdate(Long runner, Instant now)
        { return rows.values().stream().filter(c -> c.runnerId().equals(runner) && (c.status().equals("QUEUED")
                || c.status().equals("LEASED") && c.leaseUntil().isBefore(now)))
                .map(RunnerCommandRecord::commandId).sorted().findFirst().orElse(null); }
        @Override public int markLeased(Long runner, Long id, Instant now, Instant until)
        { RunnerCommandRecord old = rows.get(id); if (old == null || !old.runnerId().equals(runner)
                || !(old.status().equals("QUEUED")
                || old.status().equals("LEASED") && old.leaseUntil().isBefore(now))) return 0;
          rows.put(id, with(old, id, "LEASED", until, old.attemptCount() + 1)); return 1; }
        @Override public int markRunning(Long runner, Long id, int attempt, Instant now, Instant until)
        { return leaseMatches(runner, id, attempt, now, until) ? change(id, "LEASED", "RUNNING", null) : 0; }
        @Override public int complete(Long runner, Long id, int attempt, String status, String result,
                String errorCode, Instant now)
        { return belongsToAttempt(runner, id, attempt) ? change(id, "RUNNING", status, null) : 0; }
        @Override public int markRejected(Long runner, Long id, int attempt, Instant now,
                String result, String errorCode, Instant until)
        { return leaseMatches(runner, id, attempt, now, until) ? change(id, "LEASED", "REJECTED", null) : 0; }
        @Override public int advanceReconciled(Long runner, Long id, int attempt, String previous, String next, Instant now)
        { return rows.containsKey(id) && rows.get(id).runnerId().equals(runner)
                && rows.get(id).attemptCount() == attempt
                ? change(id, previous, next, null) : 0; }
        private boolean leaseMatches(Long runner, Long id, int attempt, Instant now, Instant until)
        { RunnerCommandRecord current = rows.get(id); return current != null && until.equals(current.leaseUntil())
                && !until.isBefore(now) && belongsToAttempt(runner, id, attempt); }
        private boolean belongsToAttempt(Long runner, Long id, int attempt)
        { RunnerCommandRecord current = rows.get(id); return current != null
                && current.runnerId().equals(runner) && current.attemptCount() == attempt; }
        private int change(Long id, String previous, String next, Instant until)
        { RunnerCommandRecord old = rows.get(id); if (old == null || !old.status().equals(previous)) return 0;
          rows.put(id, with(old, id, next, until)); return 1; }
        private RunnerCommandRecord with(RunnerCommandRecord old, Long id, String status, Instant until)
        { return with(old, id, status, until, old.attemptCount()); }
        private RunnerCommandRecord with(RunnerCommandRecord old, Long id, String status, Instant until, int attempt)
        { return new RunnerCommandRecord(id, old.commandUuid(), old.runnerId(), old.commandType(), old.payload(),
                old.idempotencyKey(), status, until, attempt, old.result(), old.errorCode(),
                old.createdAt(), old.updatedAt()); }
    }
}
