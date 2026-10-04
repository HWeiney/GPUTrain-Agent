package com.ruoyi.train.runner.process;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.protocol.RunnerProcessStatus;

class ProcessStateStoreTest
{
    @TempDir Path root;

    @Test void atomicallyReplacesStateAndKeepsTerminalResult() throws Exception
    {
        var store = new ProcessStateStore(root);
        UUID id = UUID.randomUUID();
        ManagedProcess running = new ManagedProcess(id, "key", 1234L, Instant.parse("2026-01-01T00:00:00Z"),
                root.toString(), "/usr/bin/java", "digest", root.resolve("out").toString(),
                root.resolve("err").toString(), RunnerProcessStatus.RUNNING, null, 1);
        store.save(running);
        assertEquals(List.of(running), store.loadAll());
        store.markTerminal(id, RunnerProcessStatus.SUCCEEDED, 0);
        assertEquals(RunnerProcessStatus.SUCCEEDED, store.loadAll().get(0).status());
        assertEquals(0, store.loadAll().get(0).exitCode());
        try (var files = Files.list(root.resolve(".gputrain/state")))
        { assertEquals(List.of(id + ".json"), files.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test void rejectsStateDirectorySymlink() throws Exception
    {
        Path outside = Files.createTempDirectory("state-outside");
        Files.createDirectory(root.resolve(".gputrain"));
        Files.createSymbolicLink(root.resolve(".gputrain/state"), outside);
        assertThrows(java.io.IOException.class, () -> new ProcessStateStore(root));
    }

    @Test void legacyStateWithoutAttemptFailsClosed() throws Exception
    {
        var store = new ProcessStateStore(root);
        UUID id = UUID.randomUUID();
        Files.writeString(root.resolve(".gputrain/state/" + id + ".json"),
                "{\"commandId\":\"" + id + "\",\"idempotencyKey\":\"legacy\",\"pid\":1234," +
                "\"processStartedAt\":\"2026-01-01T00:00:00Z\",\"workingDirectory\":\"/tmp\"," +
                "\"executable\":\"/usr/bin/java\",\"argumentsSha256\":\"digest\"," +
                "\"stdoutPath\":\"/tmp/out\",\"stderrPath\":\"/tmp/err\",\"status\":\"RUNNING\"}");
        assertThrows(java.io.IOException.class, store::loadAll);
    }
}
