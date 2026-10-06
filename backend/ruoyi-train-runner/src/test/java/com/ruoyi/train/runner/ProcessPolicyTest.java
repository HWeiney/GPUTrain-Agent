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

    @Test void bashIsLimitedToTheFixedDocOwlTrainingProfile() throws Exception
    {
        Properties properties = RunnerConfigTest.properties(root);
        properties.setProperty("executableAllowlist", java + ",/bin/bash");
        properties.setProperty("environmentAllowlist", "CUDA_VISIBLE_DEVICES,MODEL_PATH,PYTHON_BIN,NPROC_PER_NODE,MAX_STEPS,RUN_MODE,AUTO_EVAL,TRAIN_EVAL_STRATEGY,RUN_ID");
        var fixedPolicy = new ProcessPolicy(RunnerConfig.from(properties));
        Map<String, String> env = Map.of("CUDA_VISIBLE_DEVICES", "GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee,GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeef,GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee0",
                "MODEL_PATH", "/model/hanw/ms-swift-imp3-research/pretrained-imp/DocOwl2",
                "PYTHON_BIN", "/home/hanw/.conda/envs/swift/bin/python", "NPROC_PER_NODE", "3",
                "MAX_STEPS", "1", "RUN_MODE", "foreground", "AUTO_EVAL", "False", "TRAIN_EVAL_STRATEGY", "no", "RUN_ID", "gputrain-test");
        List<String> args = List.of("/model/hanw/ms-swift-imp3-research/fintune_val_shell/run_swift_docowl2_logidoc.sh",
                "/model/hanw/ms-swift-imp3-research/configs/ocr_evidence/docowl2_srfund.env");
        var fixed = new ProcessSpec(root.toString(), "/bin/bash", args, env, 3600, "test");
        assertEquals(args, fixedPolicy.validate(fixed).arguments());
        var arbitrary = new ProcessSpec(root.toString(), "/bin/bash", List.of("-c", "echo unsafe"), env, 10, "bad");
        assertEquals(RunnerErrorCode.EXECUTABLE_NOT_ALLOWED,
                assertThrows(ProcessPolicyException.class, () -> fixedPolicy.validate(arbitrary)).code());
    }
}
