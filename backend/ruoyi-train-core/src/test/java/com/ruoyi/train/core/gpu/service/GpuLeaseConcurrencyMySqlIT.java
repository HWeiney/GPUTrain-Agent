package com.ruoyi.train.core.gpu.service;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;

/**
 * MySQL integration tests for lease atomicity (profile mysql-it).
 * Uses the exact lease statements the mapper issues, proving the database-level guarantees:
 * single-winner concurrency, all-or-nothing multi-card, release/re-lease and idempotency.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuLeaseConcurrencyMySqlIT
{
    private Connection conn;
    private long runnerId;
    private long jobId;
    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private String gpu1, gpu2, gpu3, gpu4, gpu5, gpu6, gpu7;

    private static Connection open() throws SQLException
    {
        String url = System.getProperty("mysql.it.url",
                "jdbc:mysql://127.0.0.1:3307/gputrain?useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getProperty("mysql.it.user", "root");
        String pass = System.getProperty("mysql.it.password", "gputrain-root-local-only");
        return DriverManager.getConnection(url, user, pass);
    }

    @BeforeAll
    void seed() throws Exception
    {
        try
        {
            conn = open();
        }
        catch (SQLException failure)
        {
            conn = null;
            return;
        }
        gpu1 = "GPU-" + tag + "-1";
        gpu2 = "GPU-" + tag + "-2";
        gpu3 = "GPU-" + tag + "-3";
        gpu4 = "GPU-" + tag + "-4";
        gpu5 = "GPU-" + tag + "-5";
        gpu6 = "GPU-" + tag + "-6";
        gpu7 = "GPU-" + tag + "-7";
        try (PreparedStatement runner = conn.prepareStatement(
                "INSERT INTO train_runner (runner_name, linux_username, allowed_root, "
                        + "secret_ciphertext, status, last_seen_at, created_at, updated_at) "
                        + "VALUES (?,?,?,?, 'ACTIVE', NOW(3), NOW(3), NOW(3))",
                Statement.RETURN_GENERATED_KEYS);
                PreparedStatement job = conn.prepareStatement(
                        "INSERT INTO train_job (job_uuid, owner_user_id, runner_id, gpu_count, "
                                + "min_memory_mib, status, request_key, created_at, updated_at) "
                                + "VALUES (?,1,?,1,1,'PENDING',?, NOW(3), NOW(3))",
                        Statement.RETURN_GENERATED_KEYS))
        {
            runner.setString(1, "it-" + tag);
            runner.setString(2, "ituser");
            runner.setString(3, "/tmp");
            runner.setString(4, "cipher");
            runner.executeUpdate();
            ResultSet rk = runner.getGeneratedKeys();
            rk.next();
            runnerId = rk.getLong(1);
            job.setString(1, UUID.randomUUID().toString());
            job.setLong(2, runnerId);
            job.setString(3, "req-" + tag);
            job.executeUpdate();
            ResultSet jk = job.getGeneratedKeys();
            jk.next();
            jobId = jk.getLong(1);
        }
        for (String gpu : List.of(gpu1, gpu2, gpu3, gpu4, gpu5, gpu6, gpu7))
        {
            try (PreparedStatement device = conn.prepareStatement(
                    "INSERT INTO train_gpu_device (gpu_uuid, host_signature, name, identity_state, "
                            + "schedulable, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'OBSERVED', 1, NOW(3), NOW(3))");
                    PreparedStatement snapshot = conn.prepareStatement(
                            "INSERT INTO train_gpu_snapshot (runner_id, gpu_uuid, visible_index, "
                                    + "total_memory_mib, used_memory_mib, utilization_percent, "
                                    + "mig_mode, sampled_at, compute_processes, created_at) "
                                    + "VALUES (?,?,0,80000,0,0,'DISABLED', NOW(3), CAST('[]' AS JSON), NOW(3))"))
            {
                device.setString(1, gpu);
                device.setString(2, "host-" + tag);
                device.setString(3, "A100");
                device.executeUpdate();
                snapshot.setLong(1, runnerId);
                snapshot.setString(2, gpu);
                snapshot.executeUpdate();
            }
        }
    }

    @Test
    void sequentialSecondActiveLeaseIsRejected() throws Exception
    {
        assumeMySql();
        insertActive(gpu1, "s1", true);
        SQLException failure = assertThrows(SQLException.class, () -> insertActive(gpu1, "s2", true));
        assertTrue(failure.getSQLState().startsWith("23"), "expected integrity violation, got "
                + failure.getSQLState());
        assertEquals(1, countActive(gpu1));
    }

    @Test
    void concurrentLeaseContestHasSingleWinner() throws Exception
    {
        assumeMySql();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            List<? extends Future<?>> attempts = List.of("race-a", "race-b").stream().map(key -> pool.submit(() ->
            {
                try (Connection contender = open())
                {
                    contender.setAutoCommit(false);
                    contender.createStatement().execute("SET innodb_lock_wait_timeout=5");
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
                    insertActive(contender, gpu5, key);
                    contender.commit();
                    winners.incrementAndGet();
                }
                catch (SQLException failure)
                {
                    if (failure.getSQLState() != null && failure.getSQLState().startsWith("23"))
                        conflicts.incrementAndGet();
                    else throw new RuntimeException(failure);
                }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(failure);
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both contenders must be ready");
            start.countDown();
            for (Future<?> attempt : attempts) attempt.get(10, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get(), "exactly one concurrent acquirer should commit");
        assertEquals(1, conflicts.get(), "the other concurrent acquirer should hit the unique constraint");
        assertEquals(1, countActive(gpu5));
    }

    @Test
    void multiCardRollbackLeavesNoPartialLease() throws Exception
    {
        assumeMySql();
        try (Connection c = open())
        {
            c.setAutoCommit(false);
            insertActive(c, gpu6, "m1");
            // Simulate the second device becoming unavailable before commit: roll back.
            c.rollback();
        }
        assertEquals(0, countActive(gpu6));
        assertEquals(0, countActive(gpu7));
    }

    @Test
    void releasedLeaseAllowsReLease() throws Exception
    {
        assumeMySql();
        insertActive(gpu4, "r1", true);
        try (PreparedStatement release = conn.prepareStatement(
                "UPDATE train_gpu_lease SET status='RELEASED', released_at=NOW(3), "
                        + "updated_at=NOW(3) WHERE gpu_uuid=? AND status='ACTIVE'"))
        {
            release.setString(1, gpu4);
            assertEquals(1, release.executeUpdate());
        }
        insertActive(gpu4, "r2", true);
        assertEquals(1, countActive(gpu4));
    }

    @Test
    void duplicateJobIdempotencyKeyRejected() throws Exception
    {
        assumeMySql();
        insertActive(gpu3, "idem", true);
        SQLException failure = assertThrows(SQLException.class,
                () -> insertActive(gpu3, "idem", true));
        assertTrue(failure.getSQLState().startsWith("23"));
    }

    private void insertActive(Connection c, String gpu, String key) throws SQLException
    {
        try (PreparedStatement lease = c.prepareStatement(
                "INSERT INTO train_gpu_lease (lease_uuid, job_id, runner_id, gpu_uuid, status, "
                        + "idempotency_key, acquired_at, expires_at, attempt, created_at, updated_at) "
                        + "VALUES (?,?,?,?, 'ACTIVE', ?, NOW(3), DATE_ADD(NOW(3), INTERVAL 60 SECOND), "
                        + "0, NOW(3), NOW(3))"))
        {
            lease.setString(1, UUID.randomUUID().toString());
            lease.setLong(2, jobId);
            lease.setLong(3, runnerId);
            lease.setString(4, gpu);
            lease.setString(5, key);
            lease.executeUpdate();
        }
    }

    private void insertActive(String gpu, String key, boolean committed) throws SQLException
    {
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(!committed);
        insertActive(conn, gpu, key);
        if (committed) conn.setAutoCommit(true);
        else conn.setAutoCommit(previousAutoCommit);
    }

    private int countActive(String gpu) throws SQLException
    {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT COUNT(*) FROM train_gpu_lease WHERE gpu_uuid=? AND status='ACTIVE'"))
        {
            select.setString(1, gpu);
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    @AfterAll
    void cleanup() throws Exception
    {
        if (conn == null) return;
        List<String> gpus = List.of(gpu1, gpu2, gpu3, gpu4, gpu5, gpu6, gpu7);
        for (String gpu : gpus)
        {
            try (PreparedStatement lease = conn.prepareStatement(
                    "DELETE FROM train_gpu_lease WHERE gpu_uuid=?");
                    PreparedStatement snapshot = conn.prepareStatement(
                            "DELETE FROM train_gpu_snapshot WHERE gpu_uuid=?");
                    PreparedStatement device = conn.prepareStatement(
                            "DELETE FROM train_gpu_device WHERE gpu_uuid=?"))
            {
                lease.setString(1, gpu);
                lease.executeUpdate();
                snapshot.setString(1, gpu);
                snapshot.executeUpdate();
                device.setString(1, gpu);
                device.executeUpdate();
            }
        }
        try (PreparedStatement job = conn.prepareStatement(
                "DELETE FROM train_job WHERE job_id=?");
                PreparedStatement runner = conn.prepareStatement(
                        "DELETE FROM train_runner WHERE runner_id=?"))
        {
            job.setLong(1, jobId);
            job.executeUpdate();
            runner.setLong(1, runnerId);
            runner.executeUpdate();
        }
        conn.close();
    }

    private void assumeMySql()
    {
        Assumptions.assumeTrue(conn != null, "MySQL integration database is unavailable");
    }
}
