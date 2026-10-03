package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.aop.framework.ProxyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
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
                "/data/hanw/hw_code/gputrain-test", NOW.plusSeconds(600), "enroll-request-1");

        var first = service.register(new RunnerRegistration(grant.enrollmentId(),
                grant.plaintextToken(), "runner-hanw", "hanw"), NOW);
        assertEquals("runner-hanw", first.runnerName());
        org.junit.jupiter.api.Assertions.assertFalse(grant.toString().contains(grant.plaintextToken()));
        org.junit.jupiter.api.Assertions.assertFalse(first.toString().contains(first.secret()));
        RunnerRecord stored = runners.findByName("runner-hanw");
        org.junit.jupiter.api.Assertions.assertFalse(stored.secretCiphertext().contains(first.secret()));
        assertEquals(first.secret(), new String(new RunnerCredentialCipher(new byte[32])
                .decrypt(stored.secretCiphertext()), StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertNull(enrollments.findById(grant.enrollmentId()).tokenCiphertext());
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
                NOW.plusSeconds(60), "enroll-request-2");
        assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
                grant.enrollmentId(), "wrong", "runner-hanw", "hanw"), NOW));
        assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
                grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW.plusSeconds(60)));
        assertEquals(0, runners.values.size());
    }

    @Test
    void repeatedIdempotencyKeyReturnsSameTokenWithoutAddingRows()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        RunnerEnrollmentService service = service(enrollments, new InMemoryRunners());
        Instant expires = NOW.plusSeconds(600);
        EnrollmentGrant first = service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                expires, "request-key-3");
        EnrollmentGrant retry = service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                expires, "request-key-3");

        assertEquals(first.enrollmentId(), retry.enrollmentId());
        assertEquals(first.plaintextToken(), retry.plaintextToken());
        assertEquals(1, enrollments.byId.size());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentBindingsIsRejected()
    {
        RunnerEnrollmentService service = service(new InMemoryEnrollments(), new InMemoryRunners());
        Instant expires = NOW.plusSeconds(600);
        service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw", expires, "request-key-4");
        RunnerProtocolException exception = assertThrows(RunnerProtocolException.class,
                () -> service.createEnrollment(2L, "runner-other", "other", "/data/other",
                        expires, "request-key-4"));
        assertEquals(com.ruoyi.train.runner.protocol.RunnerErrorCode.ENROLLMENT_INVALID, exception.code());
    }

    @Test
    void idempotencyKeyMatchingIsCaseSensitiveEvenIfMapperLookupIsNot()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        RunnerEnrollmentService service = service(enrollments, new InMemoryRunners());
        Instant expires = NOW.plusSeconds(600);
        service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw", expires, "Key");
        enrollments.caseInsensitiveIdempotencyLookup = true;
        RunnerProtocolException exception = assertThrows(RunnerProtocolException.class,
                () -> service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw", expires, "key"));
        assertEquals(com.ruoyi.train.runner.protocol.RunnerErrorCode.ENROLLMENT_INVALID, exception.code());
        assertEquals(1, enrollments.byId.size());
    }

    @Test
    void enrollmentRereadTransactionsUseReadCommittedIsolation() throws Exception
    {
        Transactional create = RunnerEnrollmentService.class.getMethod("createEnrollment", long.class,
                String.class, String.class, String.class, Instant.class, String.class).getAnnotation(Transactional.class);
        Transactional register = RunnerEnrollmentService.class.getMethod("register",
                RunnerRegistration.class, Instant.class).getAnnotation(Transactional.class);
        assertNotNull(create);
        assertNotNull(register);
        assertEquals(Isolation.READ_COMMITTED, create.isolation());
        assertEquals(Isolation.READ_COMMITTED, register.isolation());
        assertFalse(create.readOnly());
    }

    @Test
    void concurrentDuplicateInsertReadsAndReturnsWinningToken()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        enrollments.winnerForNextDuplicate = record("concurrent-key", "winner-token", NOW.plusSeconds(600));
        RunnerEnrollmentService service = service(enrollments, new InMemoryRunners());
        SnapshotTransactionManager transactionManager = new SnapshotTransactionManager(
                enrollments, new InMemoryRunners());
        RunnerEnrollmentService transactional = transactionalProxy(service, transactionManager);
        EnrollmentGrant grant = transactional.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                NOW.plusSeconds(600), "concurrent-key");
        assertEquals("winner-token", grant.plaintextToken());
        assertEquals(1, enrollments.byId.size());
        assertEquals(1, transactionManager.commitCount);
    }

    @Test
    void concurrentRegistrationLoserClassifiesTheCommittedConsume()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        RunnerEnrollmentService service = service(enrollments, new InMemoryRunners());
        EnrollmentGrant grant = service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                NOW.plusSeconds(600), "registration-race-key");
        enrollments.consumeAsConcurrentLoser = true;

        RunnerProtocolException exception = assertThrows(RunnerProtocolException.class,
                () -> service.register(new RunnerRegistration(grant.enrollmentId(), grant.plaintextToken(),
                        "runner-hanw", "hanw"), NOW));
        assertEquals(com.ruoyi.train.runner.protocol.RunnerErrorCode.ENROLLMENT_ALREADY_USED, exception.code());
    }

    @Test
    void registerRollsBackTokenConsumptionWhenRunnerInsertFails()
    {
        InMemoryEnrollments enrollments = new InMemoryEnrollments();
        InMemoryRunners runners = new InMemoryRunners();
        RunnerEnrollmentService service = service(enrollments, runners);
        EnrollmentGrant grant = service.createEnrollment(1L, "runner-hanw", "hanw", "/data/hanw",
                NOW.plusSeconds(600), "rollback-key");
        runners.failInsert = true;
        RunnerEnrollmentService transactional = transactionalProxy(service,
                new SnapshotTransactionManager(enrollments, runners));

        assertThrows(IllegalStateException.class, () -> transactional.register(new RunnerRegistration(
                grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW));
        RunnerEnrollmentRecord restored = enrollments.findById(grant.enrollmentId());
        org.junit.jupiter.api.Assertions.assertNull(restored.consumedAt());
        assertNotNull(restored.tokenCiphertext());
        assertEquals(0, runners.values.size());
    }

    private static RunnerEnrollmentService transactionalProxy(RunnerEnrollmentService service,
            PlatformTransactionManager manager)
    {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (RunnerEnrollmentService) factory.getProxy();
    }

    private static RunnerEnrollmentService service(InMemoryEnrollments enrollments, InMemoryRunners runners)
    {
        return new RunnerEnrollmentService(enrollments, runners,
                new EnrollmentTokenDigester("pepper".getBytes(StandardCharsets.UTF_8)),
                new RunnerCredentialCipher(new byte[32]), new java.security.SecureRandom());
    }

    private static RunnerEnrollmentRecord record(String idempotencyKey, String token, Instant expiresAt)
    {
        EnrollmentTokenDigester digester = new EnrollmentTokenDigester("pepper".getBytes(StandardCharsets.UTF_8));
        RunnerCredentialCipher cipher = new RunnerCredentialCipher(new byte[32]);
        return new RunnerEnrollmentRecord(null, 1L, "runner-hanw", "hanw", "/data/hanw",
                digester.digest(token), cipher.encrypt(token.getBytes(StandardCharsets.UTF_8)),
                idempotencyKey, expiresAt, null, NOW);
    }

    private static final class InMemoryEnrollments implements RunnerEnrollmentMapper
    {
        private final Map<Long, RunnerEnrollmentRecord> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);
        private RunnerEnrollmentRecord winnerForNextDuplicate;
        private boolean caseInsensitiveIdempotencyLookup;
        private boolean consumeAsConcurrentLoser;
        private Map<Long, RunnerEnrollmentRecord> snapshot() { return new HashMap<>(byId); }
        private void restore(Map<Long, RunnerEnrollmentRecord> snapshot)
        {
            byId.clear();
            byId.putAll(snapshot);
        }

        @Override public int insert(RunnerEnrollmentRecord e)
        {
            if (winnerForNextDuplicate != null)
            {
                RunnerEnrollmentRecord winner = winnerForNextDuplicate;
                winnerForNextDuplicate = null;
                long winnerId = ids.getAndIncrement();
                byId.put(winnerId, new RunnerEnrollmentRecord(winnerId, winner.ownerUserId(), winner.runnerName(),
                        winner.linuxUsername(), winner.allowedRoot(), winner.tokenDigest(), winner.tokenCiphertext(),
                        winner.idempotencyKey(), winner.expiresAt(), winner.consumedAt(), winner.createdAt()));
                throw new DuplicateKeyException("simulated concurrent unique-key winner");
            }
            long id = ids.getAndIncrement();
            byId.put(id, new RunnerEnrollmentRecord(id, e.ownerUserId(), e.runnerName(), e.linuxUsername(),
                    e.allowedRoot(), e.tokenDigest(), e.tokenCiphertext(), e.idempotencyKey(), e.expiresAt(), e.consumedAt(), e.createdAt()));
            return 1;
        }
        @Override public RunnerEnrollmentRecord findById(Long id) { return byId.get(id); }
        @Override public RunnerEnrollmentRecord findByTokenDigest(String digest)
        {
            return byId.values().stream().filter(e -> e.tokenDigest().equals(digest)).findFirst().orElse(null);
        }
        @Override public RunnerEnrollmentRecord findByIdempotencyKey(String key)
        {
            return byId.values().stream().filter(e -> caseInsensitiveIdempotencyLookup
                    ? e.idempotencyKey().equalsIgnoreCase(key) : e.idempotencyKey().equals(key))
                    .findFirst().orElse(null);
        }
        @Override public int consume(Long id, String digest, Instant at)
        {
            RunnerEnrollmentRecord e = byId.get(id);
            if (consumeAsConcurrentLoser && e != null)
            {
                byId.put(id, new RunnerEnrollmentRecord(id, e.ownerUserId(), e.runnerName(), e.linuxUsername(),
                        e.allowedRoot(), e.tokenDigest(), null, e.idempotencyKey(), e.expiresAt(), at, e.createdAt()));
                consumeAsConcurrentLoser = false;
                return 0;
            }
            if (e == null || !e.tokenDigest().equals(digest) || e.consumedAt() != null
                    || !e.expiresAt().isAfter(at)) return 0;
            byId.put(id, new RunnerEnrollmentRecord(id, e.ownerUserId(), e.runnerName(), e.linuxUsername(),
                    e.allowedRoot(), e.tokenDigest(), null, e.idempotencyKey(), e.expiresAt(), at, e.createdAt()));
            return 1;
        }
    }

    private static final class InMemoryRunners implements RunnerMapper
    {
        private final Map<Long, RunnerRecord> values = new HashMap<>();
        private long nextId = 1;
        private boolean failInsert;
        private Map<Long, RunnerRecord> snapshot() { return new HashMap<>(values); }
        private void restore(Map<Long, RunnerRecord> snapshot)
        {
            values.clear();
            values.putAll(snapshot);
        }
        @Override public int insert(RunnerRecord r)
        {
            if (failInsert)
                throw new IllegalStateException("simulated Runner insert failure");
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

    private static final class SnapshotTransactionManager implements PlatformTransactionManager
    {
        private final InMemoryEnrollments enrollments;
        private final InMemoryRunners runners;
        private Map<Long, RunnerEnrollmentRecord> enrollmentSnapshot;
        private Map<Long, RunnerRecord> runnerSnapshot;
        private int commitCount;

        private SnapshotTransactionManager(InMemoryEnrollments enrollments, InMemoryRunners runners)
        {
            this.enrollments = enrollments;
            this.runners = runners;
        }

        @Override public TransactionStatus getTransaction(TransactionDefinition definition)
        {
            enrollmentSnapshot = enrollments.snapshot();
            runnerSnapshot = runners.snapshot();
            return new SimpleTransactionStatus();
        }

        @Override public void commit(TransactionStatus status)
        {
            commitCount++;
            enrollmentSnapshot = null;
            runnerSnapshot = null;
        }

        @Override public void rollback(TransactionStatus status)
        {
            enrollments.restore(enrollmentSnapshot);
            runners.restore(runnerSnapshot);
            enrollmentSnapshot = null;
            runnerSnapshot = null;
        }
    }
}
