package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.process.*;
import com.ruoyi.train.runner.protocol.*;

class ProcessSupervisorTest
{
    @TempDir Path root;
    @TempDir Path outside;
    static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    static List<String> args(String... fixtureArgs)
    {
        List<String> args = new ArrayList<>(List.of("-cp", Path.of("target/classes").toAbsolutePath().toString(), "com.ruoyi.train.runner.fixture.FixtureMain"));
        args.addAll(List.of(fixtureArgs)); return args;
    }
    @Test void startsOnlyOnceObservesExitAndConfinesLogs() throws Exception
    {
        var policy = new ProcessPolicy(RunnerConfig.from(RunnerConfigTest.properties(root)));
        var spec = policy.validate(new ProcessSpec(root.toString(), java(), args("--mode", "success"), Map.of(), 10, "same"));
        try (var supervisor = new ProcessSupervisor())
        {
            UUID id = UUID.randomUUID();
            var first = supervisor.start(id, spec);
            assertEquals(first.pid(), supervisor.start(id, spec).pid());
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (supervisor.observe(id).status() == RunnerProcessStatus.RUNNING) Thread.sleep(10); });
            assertEquals(RunnerProcessStatus.SUCCEEDED, supervisor.observe(id).status());
            assertEquals(0, supervisor.observe(id).exitCode());
            assertEquals("fixture success\n", Files.readString(spec.stdoutPath()));
            assertTrue(spec.stdoutPath().startsWith(root.toRealPath().resolve(".gputrain/logs")));
            assertEquals(RunnerProcessStatus.SUCCEEDED, supervisor.stop(id).status());
        }
    }
    @Test void stopIsIdempotentAndTimeoutTerminates() throws Exception
    {
        var policy = new ProcessPolicy(RunnerConfig.from(RunnerConfigTest.properties(root)));
        var spec = policy.validate(new ProcessSpec(root.toString(), java(), args("--mode", "sleep", "--seconds", "30"), Map.of(), 1, "sleep"));
        try (var supervisor = new ProcessSupervisor())
        {
            UUID id = UUID.randomUUID(); var started = supervisor.start(id, spec);
            assertTrue(ProcessLookup.system().find(started.pid()).orElseThrow().alive());
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (supervisor.observe(id).status() == RunnerProcessStatus.RUNNING) Thread.sleep(20); });
            assertEquals(RunnerProcessStatus.FAILED, supervisor.stop(id).status());
            assertEquals(RunnerErrorCode.PROCESS_TIMEOUT, supervisor.observe(id).errorCode());
            assertEquals(supervisor.stop(id), supervisor.stop(id));
            assertFalse(ProcessHandle.of(started.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test void literalArgumentsReachProcessAndInheritedEnvironmentIsCleared() throws Exception
    {
        var policy = new ProcessPolicy(RunnerConfig.from(RunnerConfigTest.properties(root)));
        List<String> arguments = List.of("-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
            Probe.class.getName(), "literal;touch never-created", "$(touch never-created)");
        var spec = policy.validate(new ProcessSpec(root.toString(), java(), arguments, Map.of("LANG", "C"), 10, "probe"));
        try (var supervisor = new ProcessSupervisor())
        {
            UUID id = UUID.randomUUID(); supervisor.start(id, spec);
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (supervisor.observe(id).status() == RunnerProcessStatus.RUNNING) Thread.sleep(10); });
            assertEquals("literal;touch never-created\n$(touch never-created)\nC\nnull\nnull\n", Files.readString(spec.stdoutPath()));
            assertFalse(Files.exists(root.resolve("never-created")));
            assertEquals(RunnerErrorCode.PROCESS_ALREADY_STARTED, assertThrows(ProcessPolicyException.class,
                () -> supervisor.start(UUID.randomUUID(), spec)).code());
        }
    }
    @Test void refusesPreexistingLogSymlinkWithoutTouchingTarget() throws Exception
    {
        var policy = new ProcessPolicy(RunnerConfig.from(RunnerConfigTest.properties(root)));
        var spec = policy.validate(new ProcessSpec(root.toString(), java(), args("--mode", "success"), Map.of(), 10, "link"));
        Path target = Files.writeString(outside.resolve("untouched"), "original");
        Files.createSymbolicLink(spec.stdoutPath(), target);
        try (var supervisor = new ProcessSupervisor())
        {
            assertThrows(FileAlreadyExistsException.class, () -> supervisor.start(UUID.randomUUID(), spec));
            assertEquals("original", Files.readString(target));
        }
    }
    public static class Probe
    {
        public static void main(String[] args)
        {
            for (String arg : args) System.out.println(arg);
            System.out.println(System.getenv("LANG"));
            System.out.println(System.getenv("HOME"));
            System.out.println(System.getenv("PATH"));
        }
    }
}
