package com.ruoyi.train.runner.process;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.protocol.RunnerProcessStatus;

class ProcessLogRaceTest
{
    @TempDir Path root;
    @TempDir Path outside;

    @Test void replacementBetweenReservationAndSpawnCannotRedirectOutput() throws Exception
    {
        Path logs = Files.createDirectories(root.resolve(".gputrain/logs")).toRealPath();
        Path stdout = logs.resolve("stdout.log"); Path stderr = logs.resolve("stderr.log");
        Path target = Files.writeString(outside.resolve("target"), "untouched");
        Path original = logs.resolve("original.stdout.log");
        var spec = spec(stdout, stderr);
        try (ProcessSupervisor supervisor = new ProcessSupervisor(builder -> {
            Files.move(stdout, original);
            Files.createSymbolicLink(stdout, target);
            return builder.start();
        }))
        {
            UUID id = UUID.randomUUID(); supervisor.start(id, spec);
            awaitExit(supervisor, id);
            assertEquals("untouched", Files.readString(target));
            assertEquals("fixture success\n", Files.readString(original));
        }
    }

    @Test void directoryReplacementAfterOpenCannotRedirectEitherStream() throws Exception
    {
        Path logs = Files.createDirectories(root.resolve(".gputrain/logs")).toRealPath();
        Path retained = root.resolve("retained-logs");
        var spec = spec(logs.resolve("stdout.log"), logs.resolve("stderr.log"));
        try (ProcessSupervisor supervisor = new ProcessSupervisor(builder -> {
            Files.move(logs, retained);
            Files.createSymbolicLink(logs, outside);
            return builder.start();
        }))
        {
            UUID id = UUID.randomUUID(); supervisor.start(id, spec); awaitExit(supervisor, id);
            assertFalse(Files.exists(outside.resolve("stdout.log")));
            assertFalse(Files.exists(outside.resolve("stderr.log")));
            assertEquals("fixture success\n", Files.readString(retained.resolve("stdout.log")));
        }
    }

    private ValidatedProcessSpec spec(Path stdout, Path stderr) throws Exception
    {
        return new ValidatedProcessSpec(root.toRealPath(), Path.of(System.getProperty("java.home"), "bin", "java").toRealPath(),
            List.of("-cp", Path.of("target/classes").toAbsolutePath().toString(), "com.ruoyi.train.runner.fixture.FixtureMain", "--mode", "success"),
            Map.of(), 10, UUID.randomUUID().toString(), stdout, stderr);
    }

    private static void awaitExit(ProcessSupervisor supervisor, UUID id)
    {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (supervisor.observe(id).status() == RunnerProcessStatus.RUNNING) Thread.sleep(10);
        });
        assertEquals(RunnerProcessStatus.SUCCEEDED, supervisor.observe(id).status());
    }
}
