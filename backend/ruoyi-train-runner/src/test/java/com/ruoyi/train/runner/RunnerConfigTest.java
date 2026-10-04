package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.train.runner.config.RunnerConfig;

class RunnerConfigTest
{
    @TempDir Path root;
    static Properties properties(Path root) throws Exception
    {
        Path secret = root.resolve("secret");
        Files.writeString(secret, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        Properties p = new Properties();
        p.setProperty("controlPlaneUrl", "http://127.0.0.1:8080");
        p.setProperty("runnerId", UUID.randomUUID().toString());
        p.setProperty("credentialFile", secret.toString());
        p.setProperty("allowedRoot", root.toString());
        p.setProperty("executableAllowlist", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        p.setProperty("environmentAllowlist", "LANG");
        return p;
    }
    @Test void rejectsNonLoopbackHttpControlPlane() throws Exception
    {
        for (String url : List.of("http://10.31.118.10:8080", "http://localhost.evil.test", "https://user:pass@host", "https://host/path", "https://host?secret=x", "http://127.0.0.1.evil.test"))
        {
            Properties p = properties(root); p.setProperty("controlPlaneUrl", url);
            assertThrows(IllegalArgumentException.class, () -> RunnerConfig.from(p));
        }
    }
    @Test void acceptsHttpsAndCanonicalizesAllowedRoot() throws Exception
    {
        Properties p = properties(root); p.setProperty("controlPlaneUrl", "https://control.example.test");
        assertEquals(root.toRealPath(), RunnerConfig.from(p).allowedRoot());
    }
    @Test void rejectsReadableSecretAndInvalidIntervals() throws Exception
    {
        Properties p = properties(root);
        Files.setPosixFilePermissions(root.resolve("secret"), PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IllegalArgumentException.class, () -> RunnerConfig.from(p).readSecret());
        Properties invalid = properties(root); invalid.setProperty("heartbeatIntervalSeconds", "0");
        assertThrows(IllegalArgumentException.class, () -> RunnerConfig.from(invalid));
    }
}
