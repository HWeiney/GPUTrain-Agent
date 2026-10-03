package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.runner.domain.RunnerEnrollmentRecord;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerEnrollmentMapper;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.security.EnrollmentTokenDigester;
import com.ruoyi.train.core.runner.security.RunnerCredentialCipher;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRegistration;

class RunnerEnrollmentServiceTest
{
    private static final Instant NOW = Instant.now();

    @Test
    void enrollmentCanBeConsumedOnlyOnce()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        InMemoryRunners runners = new InMemoryRunners();
        RunnerEnrollmentService service = service(enrollments, runners);
        EnrollmentGrant grant = service.createEnrollment(1L, "runner-hanw", "hanw",
                "/data/hanw/hw_code/gputrain-test", NOW.plusSeconds(600));

        var first = service.register(new RunnerRegistration(grant.enrollmentId(),
                grant.plaintextToken(), "runner-hanw", "hanw"), NOW);
        assertEquals("runner-hanw", first.runnerName());
        org.junit.jupiter.api.Assertions.assertFalse(grant.toString().contains(grant.plaintextToken()));
        org.junit.jupiter.api.Assertions.assertFalse(first.toString().contains(first.secret()));
        RunnerRecord stored = runners.findByName("runner-hanw");
        org.junit.jupiter.api.Assertions.assertFalse(stored.secretCiphertext().contains(first.secret()));
        assertEquals(first.secret(), new String(new RunnerCredentialCipher(new byte[32])
                .decrypt(stored.secretCiphertext()), StandardCharsets.UTF_8));
        assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
                grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW));
    }

    @Test
    void invalidOrExpiredEnrollmentDoesNotCreateRunner()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        InMemoryRunners runners = new InMemoryRunners();
        RunnerEnrollmentService service = service(enrollments, runners);
        EnrollmentGrant grant = service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                NOW.plusSeconds(60));
        assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
                grant.enrollmentId(), "wrong", "runner-hanw", "hanw"), NOW));
        assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
                grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW.plusSeconds(60)));
        assertEquals(0, runners.values.size());
    }

    private static RunnerEnrollmentService service(InMemoryEnrollments enrollments, InMemoryRunners runners)
    {
        return new RunnerEnrollmentService(enrollments, runners,
                new EnrollmentTokenDigester("pepper".getBytes(StandardCharsets.UTF_8)),
                new RunnerCredentialCipher(new byte[32]), new java.security.SecureRandom());
    }

    private static final class InMemoryEnrollments implements RunnerEnrollmentMapper
    {
        private final Map<Long, RunnerEnrollmentRecord> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override public int insert(RunnerEnrollmentRecord e)
        {
            long id = ids.getAndIncrement();
            byId.put(id, new RunnerEnrollmentRecord(id, e.ownerUserId(), e.runnerName(), e.linuxUsername(),
                    e.allowedRoot(), e.tokenDigest(), e.expiresAt(), e.consumedAt(), e.createdAt()));
            return 1;
        }
        @Override public RunnerEnrollmentRecord findById(Long id) { return byId.get(id); }
        @Override public RunnerEnrollmentRecord findByTokenDigest(String digest)
        {
            return byId.values().stream().filter(e -> e.tokenDigest().equals(digest)).findFirst().orElse(null);
        }
        @Override public int consume(Long id, String digest, Instant at)
        {
            RunnerEnrollmentRecord e = byId.get(id);
            if (e == null || !e.tokenDigest().equals(digest) || e.consumedAt() != null
                    || !e.expiresAt().isAfter(at)) return 0;
            byId.put(id, new RunnerEnrollmentRecord(id, e.ownerUserId(), e.runnerName(), e.linuxUsername(),
                    e.allowedRoot(), e.tokenDigest(), e.expiresAt(), at, e.createdAt()));
            return 1;
        }
    }

    private static final class InMemoryRunners implements RunnerMapper
    {
        private final Map<Long, RunnerRecord> values = new HashMap<>();
        private long nextId = 1;
        @Override public int insert(RunnerRecord r)
        {
            long id = nextId++;
            values.put(id, new RunnerRecord(id, r.runnerName(), r.linuxUsername(), r.allowedRoot(),
                    r.secretCiphertext(), r.status(), r.lastSeenAt(), r.createdAt(), r.updatedAt()));
            return 1;
        }
        @Override public RunnerRecord findById(Long id) { return values.get(id); }
        @Override public RunnerRecord findByName(String name)
        {
            return values.values().stream().filter(r -> r.runnerName().equals(name)).findFirst().orElse(null);
        }
        @Override public int updateHeartbeat(Long id, Instant at) { throw new UnsupportedOperationException(); }
    }
}
