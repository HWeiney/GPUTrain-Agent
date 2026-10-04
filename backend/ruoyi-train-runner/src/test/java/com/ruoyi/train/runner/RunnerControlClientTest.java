package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.client.RunnerControlClient;
import com.ruoyi.train.runner.protocol.*;

class RunnerControlClientTest
{
    @TempDir Path root;
    @Test void exposesStableErrorCodeWithoutEchoingResponseAndDoesNotFollowRedirects() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner-api/v1/heartbeat", exchange -> {
            byte[] body = "{\"errorCode\":\"COMMAND_LEASE_EXPIRED\",\"message\":\"private-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/runner-api/v1/commands/poll", exchange -> {
            exchange.getResponseHeaders().add("Location", "/runner-api/v1/heartbeat");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try
        {
            Properties p = RunnerConfigTest.properties(root); p.setProperty("controlPlaneUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            var client = new RunnerControlClient(RunnerConfig.from(p));
            var error = assertThrows(RunnerProtocolException.class, () -> client.heartbeat(new RunnerHeartbeat("id", "user", Instant.now())));
            assertEquals(RunnerErrorCode.COMMAND_LEASE_EXPIRED, error.code());
            assertFalse(error.toString().contains("private-token"));
            assertTrue(assertThrows(java.io.IOException.class, client::poll).getMessage().contains("302"));
        }
        finally { server.stop(0); }
    }
    @Test void signsExactBytesWithFreshNoncesAndLeavesRegistrationUnsigned() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<Map<String,String>>();
        server.createContext("/runner-api/v1/", exchange -> {
            Map<String,String> item = new HashMap<>();
            for (String name : List.of("Id", "Timestamp", "Nonce", "Signature")) item.put(name, exchange.getRequestHeaders().getFirst("X-Runner-" + name));
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            item.put("body", new String(bytes, StandardCharsets.UTF_8));
            item.put("path", exchange.getRequestURI().getPath()); item.put("method", exchange.getRequestMethod());
            requests.add(item);
            if (exchange.getRequestURI().getPath().endsWith("register")) {
                byte[] response = "{\"runnerId\":\"id\",\"runnerName\":\"测试\",\"secret\":\"secret\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } else exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try
        {
            Properties p = RunnerConfigTest.properties(root); p.setProperty("controlPlaneUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            var client = new RunnerControlClient(RunnerConfig.from(p));
            client.heartbeat(new RunnerHeartbeat(p.getProperty("runnerId"), "测试", Instant.now()));
            assertTrue(client.poll().isEmpty());
            client.register(new RunnerRegistration(1, "enrollment-secret", "测试", "user"));
            for (var item : requests.subList(0, 2))
            {
                assertEquals(16, Base64.getUrlDecoder().decode(item.get("Nonce")).length);
                assertEquals(RunnerRequestSigner.sign(new byte[32], RunnerRequestSigner.canonical(item.get("Id"), Long.parseLong(item.get("Timestamp")), item.get("Nonce"), item.get("method"), item.get("path"), item.get("body").getBytes(StandardCharsets.UTF_8))), item.get("Signature"));
            }
            assertNotEquals(requests.get(0).get("Nonce"), requests.get(1).get("Nonce"));
            assertNull(requests.get(2).get("Signature"));
        }
        finally { server.stop(0); }
    }
}
