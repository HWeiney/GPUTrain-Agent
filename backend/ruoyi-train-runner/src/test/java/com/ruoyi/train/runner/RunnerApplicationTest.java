package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.protocol.*;

class RunnerApplicationTest
{
    @TempDir Path root;
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
            }
        }
        finally { server.stop(0); }
    }
}
