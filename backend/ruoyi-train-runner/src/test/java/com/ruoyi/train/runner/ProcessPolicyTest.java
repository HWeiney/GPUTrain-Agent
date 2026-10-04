package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.process.*;
import com.ruoyi.train.runner.protocol.*;

class ProcessPolicyTest
{
    @TempDir Path root;
    @TempDir Path outside;
    ProcessPolicy policy;
    String java;
    @BeforeEach void setup() throws Exception
    {
        policy = new ProcessPolicy(RunnerConfig.from(RunnerConfigTest.properties(root)));
        java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString();
    }
    ProcessSpec spec(Path work, String executable, Map<String,String> env)
    { return new ProcessSpec(work.toString(), executable, List.of("literal;touch", "never-created"), env, 10, "key"); }
    @Test void preservesMetacharactersAndRejectsEnvironment() throws Exception
    {
        var result = policy.validate(spec(root, java, Map.of("LANG", "C")));
        assertEquals("literal;touch", result.arguments().get(0));
        assertEquals(Map.of("LANG", "C"), result.environment());
        assertEquals(RunnerErrorCode.ENVIRONMENT_NOT_ALLOWED, assertThrows(ProcessPolicyException.class,
            () -> policy.validate(spec(root, java, Map.of("LD_PRELOAD", "evil")))).code());
        assertThrows(UnsupportedOperationException.class, () -> result.arguments().add("x"));
    }
    @Test void rejectsOutsideAndSymlinkEscape() throws Exception
    {
        assertEquals(RunnerErrorCode.WORK_DIR_OUTSIDE_ALLOWED_ROOT, assertThrows(ProcessPolicyException.class,
            () -> policy.validate(spec(outside, java, Map.of()))).code());
        Path link = Files.createSymbolicLink(root.resolve("escape"), outside);
        assertEquals(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE, assertThrows(ProcessPolicyException.class,
            () -> policy.validate(spec(link, java, Map.of()))).code());
    }
    @Test void rejectsUnlistedExecutableAndLogSymlink() throws Exception
    {
        assertEquals(RunnerErrorCode.EXECUTABLE_NOT_ALLOWED, assertThrows(ProcessPolicyException.class,
            () -> policy.validate(spec(root, "/bin/echo", Map.of()))).code());
        Files.createSymbolicLink(root.resolve(".gputrain"), outside);
        assertThrows(ProcessPolicyException.class, () -> policy.validate(spec(root, java, Map.of())));
        assertFalse(Files.exists(outside.resolve("logs")));
    }
    @Test void protectedEnvironmentCannotBeEnabledByAllowlist() throws Exception
    {
        Properties properties = RunnerConfigTest.properties(root);
        properties.setProperty("environmentAllowlist", "PATH,LD_PRELOAD,DYLD_INSERT_LIBRARIES,JAVA_TOOL_OPTIONS,RUNNER_SECRET");
        var permissive = new ProcessPolicy(RunnerConfig.from(properties));
        for (String key : List.of("PATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "JAVA_TOOL_OPTIONS", "RUNNER_SECRET"))
            assertEquals(RunnerErrorCode.ENVIRONMENT_NOT_ALLOWED, assertThrows(ProcessPolicyException.class,
                () -> permissive.validate(spec(root, java, Map.of(key, "unsafe")))).code());
    }
}
