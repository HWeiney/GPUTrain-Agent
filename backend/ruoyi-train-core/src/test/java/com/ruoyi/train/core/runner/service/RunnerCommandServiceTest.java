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
        assertEquals(first.commandId(), service.leaseNext("1", NOW.plusSeconds(31), Duration.ofSeconds(30))
                .orElseThrow().commandId());
        service.report("1", first.commandId(), report(RunnerCommandStatus.LEASED,
                RunnerCommandStatus.RUNNING));
        assertTrue(service.leaseNext("1", NOW.plusSeconds(100), Duration.ofSeconds(30)).isEmpty());
    }

    @Test void finalStateIsImmutableAndDuplicateReportSucceeds()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("finish"), 1L);
        UUID id = UUID.fromString(command.commandUuid());
        service.leaseNext("1", NOW, Duration.ofSeconds(30));
        service.report("1", id, report(RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING));
        RunnerCommandReport success = report(RunnerCommandStatus.RUNNING, RunnerCommandStatus.SUCCEEDED);
        assertEquals("SUCCEEDED", service.report("1", id, success).status());
        assertEquals("SUCCEEDED", service.report("1", id, success).status());
        assertThrows(IllegalStateException.class, () -> service.report("1", id,
                report(RunnerCommandStatus.RUNNING, RunnerCommandStatus.FAILED)));
        assertThrows(IllegalStateException.class, () -> service.report("1", id,
                report(RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)));
    }

    @Test void wrongRunnerCannotReportOrStopAndStopIsIdempotent()
    {
        RunnerCommandRecord start = service.createFixtureCommand("1", spec("stop"), 1L);
        UUID id = UUID.fromString(start.commandUuid());
        assertThrows(IllegalArgumentException.class, () -> service.report("2", id,
                report(RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)));
        assertThrows(IllegalArgumentException.class, () -> service.requestStop("2", id));
        RunnerCommandRecord stop = service.requestStop("1", id);
        assertEquals(RunnerCommandType.STOP_PROCESS.name(), stop.commandType());
        assertEquals(stop.commandUuid(), service.requestStop("1", id).commandUuid());
        assertEquals(2, commands.rows.size());
        assertNotEquals(stop.commandUuid(), start.commandUuid());
    }

    @Test void expiredLeaseCannotBecomeRunning()
    {
        RunnerCommandRecord command = service.createFixtureCommand("1", spec("expired"), 1L);
        service.leaseNext("1", NOW, Duration.ofSeconds(30));
        RunnerCommandService lateService = new RunnerCommandService(commands, new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        RunnerProtocolException error = assertThrows(RunnerProtocolException.class,
                () -> lateService.report("1", UUID.fromString(command.commandUuid()),
                        report(RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING)));
        assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, error.code());
    }

    @Test void fixtureCannotUseStopIdempotencyNamespace()
    {
        assertThrows(IllegalArgumentException.class,
                () -> service.createFixtureCommand("1", spec("stop:reserved"), 1L));
    }

    private static ProcessSpec spec(String key)
    { return new ProcessSpec("/tmp", "/bin/true", List.of(), Map.of(), 30, key); }
    private static RunnerCommandReport report(RunnerCommandStatus previous, RunnerCommandStatus status)
    { return new RunnerCommandReport(previous, status, null, null, null, null, null); }

    private static final class MemoryCommands implements RunnerCommandMapper
    {
        private final Map<Long, RunnerCommandRecord> rows = new HashMap<>();
        private long nextId = 1;
        @Override public int insert(RunnerCommandRecord command)
        { rows.put(nextId, with(command, nextId++, command.status(), command.leaseUntil())); return 1; }
        @Override public RunnerCommandRecord findById(Long id) { return rows.get(id); }
        @Override public RunnerCommandRecord findByUuid(String uuid)
        { return rows.values().stream().filter(c -> c.commandUuid().equals(uuid)).findFirst().orElse(null); }
        @Override public RunnerCommandRecord findByRunnerAndIdempotencyKey(Long runner, String key)
        { return rows.values().stream().filter(c -> c.runnerId().equals(runner) && c.idempotencyKey().equals(key))
                .findFirst().orElse(null); }
        @Override public Long selectLeaseCandidateForUpdate(Long runner, Instant now)
        { return rows.values().stream().filter(c -> c.runnerId().equals(runner) && (c.status().equals("QUEUED")
                || c.status().equals("LEASED") && c.leaseUntil().isBefore(now)))
                .map(RunnerCommandRecord::commandId).sorted().findFirst().orElse(null); }
        @Override public int markLeased(Long id, Instant now, Instant until)
        { RunnerCommandRecord old = rows.get(id); if (old == null || !(old.status().equals("QUEUED")
                || old.status().equals("LEASED") && old.leaseUntil().isBefore(now))) return 0;
          rows.put(id, with(old, id, "LEASED", until)); return 1; }
        @Override public int markRunning(Long id, Instant now, Instant until)
        { return leaseMatches(id, now, until) ? change(id, "LEASED", "RUNNING", null) : 0; }
        @Override public int complete(Long id, String status, String result, String errorCode, Instant now)
        { return change(id, "RUNNING", status, null); }
        @Override public int markRejected(Long id, Instant now, String result, String errorCode, Instant until)
        { return leaseMatches(id, now, until) ? change(id, "LEASED", "REJECTED", null) : 0; }
        private boolean leaseMatches(Long id, Instant now, Instant until)
        { RunnerCommandRecord current = rows.get(id); return current != null && until.equals(current.leaseUntil())
                && !until.isBefore(now); }
        private int change(Long id, String previous, String next, Instant until)
        { RunnerCommandRecord old = rows.get(id); if (old == null || !old.status().equals(previous)) return 0;
          rows.put(id, with(old, id, next, until)); return 1; }
        private RunnerCommandRecord with(RunnerCommandRecord old, Long id, String status, Instant until)
        { return new RunnerCommandRecord(id, old.commandUuid(), old.runnerId(), old.commandType(), old.payload(),
                old.idempotencyKey(), status, until, old.attemptCount(), old.result(), old.errorCode(),
                old.createdAt(), old.updatedAt()); }
    }
}
