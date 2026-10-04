package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.process.ManagedProcess;
import com.ruoyi.train.runner.process.ProcessStateStore;
import com.ruoyi.train.runner.process.ProcessSupervisor;
import com.ruoyi.train.runner.protocol.*;

class RunnerApplicationTest
{
    @TempDir Path root;
    @Test void refreshesRecoverySnapshotOnRetryAfterChildExits() throws Exception
    {
        Process child = sleepingChild();
        UUID id = saveChild(child);
        List<RunnerProcessSnapshot> sent = new CopyOnWriteArrayList<>();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/processes/reconcile", exchange -> {
            RunnerProcessSnapshot[] batch = json.readValue(exchange.getRequestBody(), RunnerProcessSnapshot[].class);
            sent.add(batch[0]);
            if (sent.size() == 1)
            {
                child.destroyForcibly();
                try { child.waitFor(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(503, -1);
            }
            else exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/runner-api/v1/heartbeat", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/runner-api/v1/commands/poll", exchange -> reject(exchange, "RUNNER_AUTH_INVALID"));
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, assertThrows(RunnerProtocolException.class, app::run).code());
            assertEquals(id, sent.get(0).commandId());
            assertEquals(RunnerProcessStatus.RUNNING, sent.get(0).status());
            assertEquals(RunnerProcessStatus.UNKNOWN, sent.get(1).status());
        }
        finally { child.destroyForcibly(); child.waitFor(); server.stop(0); }
    }

    @Test void recoveredStopPersistsAndReconcilesTerminalStatus() throws Exception
    {
        Process child = sleepingChild();
        UUID target = saveChild(child);
        UUID stopId = UUID.randomUUID();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        var lease = new RunnerCommandLease(stopId, RunnerCommandType.STOP_PROCESS, null, target,
                Instant.now().plusSeconds(30), 1);
        List<RunnerProcessSnapshot> sent = new CopyOnWriteArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/processes/reconcile", exchange -> {
            RunnerProcessSnapshot[] batch = json.readValue(exchange.getRequestBody(), RunnerProcessSnapshot[].class);
            sent.add(batch[0]); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/runner-api/v1/heartbeat", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            if (polls.getAndIncrement() == 0)
            {
                byte[] body = json.writeValueAsBytes(lease);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }
            else reject(exchange, "RUNNER_AUTH_INVALID");
        });
        server.createContext("/runner-api/v1/commands/" + stopId + "/reports", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, assertThrows(RunnerProtocolException.class, app::run).code());
            assertEquals(RunnerProcessStatus.RUNNING, sent.get(0).status());
            assertTrue(sent.stream().anyMatch(s -> s.status() == RunnerProcessStatus.STOPPED));
            assertEquals(RunnerProcessStatus.STOPPED, new ProcessStateStore(root).loadAll().get(0).status());
            assertFalse(child.isAlive());
        }
        finally { child.destroyForcibly(); child.waitFor(); server.stop(0); }
    }

    @Test void unknownRecoveredStopReportsFailure() throws Exception
    {
        ManagedProcess saved = new ManagedProcess(UUID.randomUUID(), "missing", Long.MAX_VALUE,
                Instant.parse("2026-01-01T00:00:00Z"), root.toString(), ProcessSupervisorTest.java(),
                "digest", root.resolve("out").toString(), root.resolve("err").toString(),
                RunnerProcessStatus.RUNNING, null, 1);
        List<RunnerCommandReport> reports = runRecoveredStop(saved);
        assertStopReports(reports, RunnerCommandStatus.FAILED, RunnerErrorCode.COMMAND_STATUS_CONFLICT);
    }

    @Test void identityMismatchRecoveredStopReportsFailureWithoutDestroyingChild() throws Exception
    {
        Process child = sleepingChild();
        try
        {
            ManagedProcess saved = new ManagedProcess(UUID.randomUUID(), "mismatched", child.pid(),
                    child.info().startInstant().orElseThrow().plusSeconds(30), root.toString(),
                    Path.of(ProcessSupervisorTest.java()).toRealPath().toString(),
                    ProcessSupervisor.argumentsSha256(ProcessSupervisorTest.args("--mode", "sleep", "--seconds", "30")),
                    root.resolve("out").toString(), root.resolve("err").toString(),
                    RunnerProcessStatus.RUNNING, null, 1);
            List<RunnerCommandReport> reports = runRecoveredStop(saved);
            assertStopReports(reports, RunnerCommandStatus.FAILED, RunnerErrorCode.PROCESS_IDENTITY_MISMATCH);
            assertTrue(child.isAlive());
        }
        finally { child.destroyForcibly(); child.waitFor(); }
    }

    @Test void alreadyStoppedRecoveredProcessReportsIdempotentSuccess() throws Exception
    {
        ManagedProcess saved = new ManagedProcess(UUID.randomUUID(), "stopped", Long.MAX_VALUE,
                Instant.parse("2026-01-01T00:00:00Z"), root.toString(), ProcessSupervisorTest.java(),
                "digest", root.resolve("out").toString(), root.resolve("err").toString(),
                RunnerProcessStatus.STOPPED, null, 1);
        assertStopReports(runRecoveredStop(saved), RunnerCommandStatus.SUCCEEDED, null);
    }

    private List<RunnerCommandReport> runRecoveredStop(ManagedProcess saved) throws Exception
    {
        new ProcessStateStore(root).save(saved);
        UUID stopId = UUID.randomUUID();
        RunnerCommandLease lease = new RunnerCommandLease(stopId, RunnerCommandType.STOP_PROCESS, null,
                saved.commandId(), Instant.now().plusSeconds(30), 1);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        List<RunnerCommandReport> reports = new CopyOnWriteArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        allowReconcile(server);
        server.createContext("/runner-api/v1/heartbeat", exchange -> {
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            if (polls.getAndIncrement() == 0)
            {
                byte[] body = json.writeValueAsBytes(lease);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }
            else reject(exchange, "RUNNER_AUTH_INVALID");
        });
        server.createContext("/runner-api/v1/commands/" + stopId + "/reports", exchange -> {
            reports.add(json.readValue(exchange.getRequestBody(), RunnerCommandReport.class));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID,
                    assertThrows(RunnerProtocolException.class, app::run).code());
            return List.copyOf(reports);
        }
        finally { server.stop(0); }
    }

    private static void assertStopReports(List<RunnerCommandReport> reports,
            RunnerCommandStatus expected, RunnerErrorCode error)
    {
        assertEquals(2, reports.size());
        assertEquals(RunnerCommandStatus.LEASED, reports.get(0).previousStatus());
        assertEquals(RunnerCommandStatus.RUNNING, reports.get(0).status());
        assertNull(reports.get(0).errorCode());
        assertEquals(RunnerCommandStatus.RUNNING, reports.get(1).previousStatus());
        assertEquals(expected, reports.get(1).status());
        assertEquals(error, reports.get(1).errorCode());
    }

    @Test void observesRecoveredChildExitAfterStartupAndReconcilesUnknown() throws Exception
    {
        Process child = sleepingChild();
        saveChild(child);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        List<RunnerProcessSnapshot> sent = new CopyOnWriteArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/processes/reconcile", exchange -> {
            sent.add(json.readValue(exchange.getRequestBody(), RunnerProcessSnapshot[].class)[0]);
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/runner-api/v1/heartbeat", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            if (polls.getAndIncrement() == 0)
            {
                child.destroyForcibly();
                try { child.waitFor(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(204, -1); exchange.close();
            }
            else reject(exchange, "RUNNER_AUTH_INVALID");
        });
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, assertThrows(RunnerProtocolException.class, app::run).code());
            assertEquals(RunnerProcessStatus.RUNNING, sent.get(0).status());
            assertTrue(sent.stream().anyMatch(s -> s.status() == RunnerProcessStatus.UNKNOWN));
        }
        finally { child.destroyForcibly(); child.waitFor(); server.stop(0); }
    }

    private Process sleepingChild() throws Exception
    {
        List<String> command = new ArrayList<>(); command.add(ProcessSupervisorTest.java());
        command.addAll(ProcessSupervisorTest.args("--mode", "sleep", "--seconds", "30"));
        return new ProcessBuilder(command).directory(root.toFile()).start();
    }

    private UUID saveChild(Process child) throws Exception
    {
        UUID id = UUID.randomUUID();
        new ProcessStateStore(root).save(new ManagedProcess(id, "saved", child.pid(),
                child.info().startInstant().orElseThrow(), root.toString(), Path.of(ProcessSupervisorTest.java()).toRealPath().toString(),
                ProcessSupervisor.argumentsSha256(ProcessSupervisorTest.args("--mode", "sleep", "--seconds", "30")),
                root.resolve("out").toString(), root.resolve("err").toString(), RunnerProcessStatus.RUNNING, null, 1));
        return id;
    }
    @Test void loadsSavedStateAndReconcilesBeforeHeartbeatOrPoll() throws Exception
    {
        UUID id = UUID.randomUUID();
        new ProcessStateStore(root).save(new ManagedProcess(id, "saved", 1234L, Instant.now(), root.toString(),
                ProcessSupervisorTest.java(), "digest", root.resolve("out").toString(), root.resolve("err").toString(),
                RunnerProcessStatus.SUCCEEDED, 0, 1));
        List<String> events = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/processes/reconcile", exchange -> {
            events.add("reconcile");
            String body = new String(exchange.getRequestBody().readAllBytes());
            assertTrue(body.contains(id.toString())); assertTrue(body.contains("SUCCEEDED"));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/runner-api/v1/heartbeat", exchange -> {
            events.add("heartbeat"); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            events.add("poll"); reject(exchange, "RUNNER_AUTH_INVALID");
        });
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID,
                    assertThrows(RunnerProtocolException.class, app::run).code());
            assertEquals("reconcile", events.get(0));
            assertTrue(events.contains("poll"));
        }
        finally { server.stop(0); }
    }
    @Test void heartbeatRejectionWhilePollWaitsPreventsReturnedLeaseFromStarting() throws Exception
    {
        UUID id = UUID.randomUUID();
        var lease = new RunnerCommandLease(id, RunnerCommandType.START_PROCESS,
            new ProcessSpec(root.toString(), ProcessSupervisorTest.java(), ProcessSupervisorTest.args("--mode", "success"), Map.of(), 10, "revoked"), null, Instant.now().plusSeconds(30), 1);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        CountDownLatch pollStarted = new CountDownLatch(1); CountDownLatch heartbeatRejected = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<RunnerApplication> application = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            pollStarted.countDown();
            try { assertTrue(heartbeatRejected.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!application.get().communicationTerminated() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(application.get().communicationTerminated());
            byte[] body = json.writeValueAsBytes(lease);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/runner-api/v1/heartbeat", exchange -> {
            try { assertTrue(pollStarted.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reject(exchange, "RUNNER_AUTH_INVALID"); heartbeatRejected.countDown();
        });
        server.createContext("/runner-api/v1/commands/" + id + "/reports", exchange -> {
            reports.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        allowReconcile(server);
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (RunnerApplication app = application(server))
        {
            application.set(app);
            Future<?> run = executor.submit(() -> { try { app.run(); } catch (Exception failure) { throw new CompletionException(failure); } });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> run.get(4, TimeUnit.SECONDS));
            assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, ((RunnerProtocolException) failure.getCause().getCause()).code());
            assertEquals(0, reports.get());
            assertFalse(Files.exists(root.resolve(".gputrain/logs")));
        }
        finally { executor.shutdownNow(); server.stop(0); }
    }
    @Test void permanentAuthenticationRejectionStopsRunWithoutRetrying() throws Exception
    {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/heartbeat", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            polls.incrementAndGet(); reject(exchange, "RUNNER_AUTH_INVALID");
        });
        allowReconcile(server);
        server.start();
        try (RunnerApplication app = application(server))
        {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, assertThrows(RunnerProtocolException.class, app::run).code()));
            assertThrows(RunnerProtocolException.class, app::tick);
            assertEquals(1, polls.get());
        }
        finally { server.stop(0); }
    }
    @Test void statusConflictIsQuarantinedInsteadOfBlockingLaterPolls() throws Exception
    {
        UUID id = UUID.randomUUID();
        var lease = new RunnerCommandLease(id, RunnerCommandType.START_PROCESS,
            new ProcessSpec(root.toString(), ProcessSupervisorTest.java(), ProcessSupervisorTest.args("--mode", "success"), Map.of(), 10, "conflict"), null, Instant.now().plusSeconds(30), 1);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicInteger polls = new AtomicInteger(); AtomicInteger reports = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            polls.incrementAndGet(); byte[] body = json.writeValueAsBytes(lease);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/runner-api/v1/commands/" + id + "/reports", exchange -> {
            reports.incrementAndGet(); reject(exchange, "COMMAND_STATUS_CONFLICT");
        });
        allowReconcile(server);
        server.start();
        try (RunnerApplication app = application(server))
        {
            app.tick(); app.tick(); app.tick();
            assertEquals(3, polls.get()); assertEquals(1, reports.get());
        }
        finally { server.stop(0); }
    }
    private RunnerApplication application(HttpServer server) throws Exception
    {
        Properties p = RunnerConfigTest.properties(root);
        p.setProperty("controlPlaneUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        return new RunnerApplication(RunnerConfig.from(p));
    }
    private static void reject(com.sun.net.httpserver.HttpExchange exchange, String code) throws java.io.IOException
    {
        byte[] body = ("{\"errorCode\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body); exchange.close();
    }
    private static void allowReconcile(HttpServer server)
    { server.createContext("/runner-api/v1/processes/reconcile", exchange -> {
        exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204, -1); exchange.close();
    }); }
    @Test void executesLeaseAndRetriesRunningReportBeforeReportingExit() throws Exception
    {
        UUID id = UUID.randomUUID();
        var lease = new RunnerCommandLease(id, RunnerCommandType.START_PROCESS,
            new ProcessSpec(root.toString(), ProcessSupervisorTest.java(), ProcessSupervisorTest.args("--mode", "success"), Map.of(), 10, "app"), null, Instant.now().plusSeconds(30), 3);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicInteger polls = new AtomicInteger(); AtomicInteger attempts = new AtomicInteger();
        List<RunnerCommandReport> reports = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            if (polls.getAndIncrement() == 0) { byte[] body = json.writeValueAsBytes(lease); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            else exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/runner-api/v1/commands/" + id + "/reports", exchange -> {
            reports.add(json.readValue(exchange.getRequestBody(), RunnerCommandReport.class));
            exchange.sendResponseHeaders(attempts.getAndIncrement() == 0 ? 503 : 204, -1); exchange.close();
        });
        allowReconcile(server);
        server.start();
        try
        {
            Properties p = RunnerConfigTest.properties(root); p.setProperty("controlPlaneUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            try (RunnerApplication app = new RunnerApplication(RunnerConfig.from(p)))
            {
                assertThrows(java.io.IOException.class, app::tick);
                assertEquals(1, polls.get());
                assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (reports.stream().noneMatch(r -> r.status() == RunnerCommandStatus.SUCCEEDED)) { app.tick(); Thread.sleep(10); } });
                assertEquals(reports.get(0), reports.get(1));
                assertEquals(RunnerCommandStatus.LEASED, reports.get(0).previousStatus());
                assertEquals(RunnerCommandStatus.RUNNING, reports.get(0).status());
                assertTrue(reports.stream().allMatch(r -> r.attempt() == 3));
                assertEquals(3, new ProcessStateStore(root).loadAll().get(0).attempt());
            }
        }
        finally { server.stop(0); }
    }
}
