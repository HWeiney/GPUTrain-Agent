package com.ruoyi.train.core.server.ssh;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.junit.jupiter.api.*;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.*;

class MinaSshGatewayTest
{
    private final ServerSecretCipher cipher = new ServerSecretCipher(new byte[32]);
    private final AtomicInteger authentications = new AtomicInteger();
    private final AtomicInteger commands = new AtomicInteger();
    private SshServer sshd;
    private KeyPair hostKey;
    private KeyPair userKey;
    private String stdout = "0.50 0.25 0.10 1/40 100\n";
    private String stderr = "";
    private long commandDelay;
    private final Duration timeout = Duration.ofSeconds(3);

    @BeforeEach void start() throws Exception
    {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        hostKey = generator.generateKeyPair();
        userKey = generator.generateKeyPair();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((user, password, session) -> {
            authentications.incrementAndGet();
            return "alice".equals(user) && "password".equals(password);
        });
        sshd.setPublickeyAuthenticator((user, key, session) -> {
            authentications.incrementAndGet();
            return "alice".equals(user) && KeyUtils.compareKeys(key, userKey.getPublic());
        });
        sshd.setCommandFactory((channel, command) -> {
            commands.incrementAndGet();
            return new OutputCommand();
        });
        sshd.start();
    }

    @AfterEach void stop() throws Exception { sshd.stop(true); }

    @Test void previewCapturesOpenSshSha256WithoutAnyAuthentication()
    {
        var preview = gateway().preview("127.0.0.1", sshd.getPort(), timeout);
        assertEquals(KeyUtils.getFingerPrint(hostKey.getPublic()), preview.sha256());
        assertEquals("ssh-rsa", preview.algorithm());
        assertEquals(0, authentications.get());
    }

    @Test void passwordLoginUsesVerifiedHostAndFixedCommand()
    {
        var result = gateway().withVerifiedSession(server(fingerprint()), password("password"),
            session -> session.exec(List.of("cat", "/proc/loadavg"), timeout, 1024));
        assertEquals(stdout, result.stdout());
        assertEquals(0, result.exitCode());
        assertTrue(authentications.get() > 0);
        assertFalse(result.toString().contains(stdout.trim()));
    }

    @Test void plainAndEncryptedOpenSshPrivateKeysAuthenticate() throws Exception
    {
        for (boolean encrypted : new boolean[] {false, true})
        {
            var output = new ByteArrayOutputStream();
            OpenSSHKeyEncryptionContext options = null;
            if (encrypted)
            {
                options = new OpenSSHKeyEncryptionContext();
                options.setCipherType("256");
                options.setPassword("passphrase");
            }
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKey, "test", options, output);
            var account = account("PRIVATE_KEY", output.toString(StandardCharsets.UTF_8),
                encrypted ? "passphrase" : null);
            assertEquals("authenticated", gateway().withVerifiedSession(server(fingerprint()), account, session -> "authenticated"));
        }
        assertTrue(authentications.get() >= 2);
    }

    @Test void deployedClasspathSupportsEd25519()
    {
        assertTrue(SecurityUtils.isEDDSACurveSupported(), "The production SSH dependencies must support Ed25519");
    }

    @Test void plainOpenSshEd25519PrivateKeyAuthenticates() throws Exception
    {
        authenticateEd25519PrivateKey(false);
    }

    @Test void encryptedOpenSshEd25519PrivateKeyAuthenticates() throws Exception
    {
        authenticateEd25519PrivateKey(true);
    }

    @Test void ed25519HostKeyCanBePreviewedPinnedAndRejectRotation() throws Exception
    {
        hostKey = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        var gateway = gateway();
        var preview = gateway.preview("127.0.0.1", sshd.getPort(), timeout);
        assertEquals("ssh-ed25519", preview.algorithm());
        assertEquals(fingerprint(), preview.sha256());
        assertEquals(0, authentications.get());
        assertEquals("verified", gateway.withVerifiedSession(server(preview.sha256()), password("password"), session -> "verified"));
        assertTrue(authentications.get() > 0);
        authentications.set(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(KeyUtils.generateKeyPair("ssh-ed25519", 256)));
        assertCode(HOST_KEY_CHANGED, () -> gateway.withVerifiedSession(server(preview.sha256()), password("password"), session -> null));
        assertEquals(0, authentications.get());
    }

    private void authenticateEd25519PrivateKey(boolean encrypted) throws Exception
    {
        userKey = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        var output = new ByteArrayOutputStream();
        OpenSSHKeyEncryptionContext options = null;
        if (encrypted)
        {
            options = new OpenSSHKeyEncryptionContext();
            options.setCipherType("256");
            options.setPassword("ed25519-test-passphrase");
        }
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKey, "ed25519-test", options, output);
        var account = account("PRIVATE_KEY", output.toString(StandardCharsets.UTF_8),
            encrypted ? "ed25519-test-passphrase" : null);
        assertEquals("authenticated", gateway().withVerifiedSession(server(fingerprint()), account, session -> "authenticated"));
        assertTrue(authentications.get() > 0);
    }

    @Test void unconfirmedAndChangedFingerprintsRejectBeforeAuthentication()
    {
        assertCode(HOST_KEY_UNCONFIRMED, () -> gateway().withVerifiedSession(server(null), password("password"), s -> null));
        assertCode(HOST_KEY_CHANGED, () -> gateway().withVerifiedSession(server("SHA256:" + "A".repeat(43)), password("password"), s -> null));
        assertEquals(0, authentications.get());
    }

    @Test void actualHostKeyRotationRejectsBeforeAuthentication() throws Exception
    {
        String previous = fingerprint();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
        assertCode(HOST_KEY_CHANGED, () -> gateway().withVerifiedSession(server(previous), password("password"), s -> null));
        assertEquals(0, authentications.get());
    }

    @Test void wrongPasswordHasSanitizedAuthenticationError()
    {
        var error = assertThrows(SshGateway.SshException.class,
            () -> gateway().withVerifiedSession(server(fingerprint()), password("do-not-leak"), s -> null));
        assertEquals(AUTH_FAILED, error.code());
        assertFalse(error.toString().contains("do-not-leak"));
        assertNull(error.getCause());
    }

    @Test void silentServerHandshakeTimesOutWithSpecificCode() throws Exception
    {
        try (var silent = new ServerSocket(0))
        {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertCode(CONNECT_TIMEOUT, () -> gateway().preview("127.0.0.1", silent.getLocalPort(), Duration.ofMillis(150))));
        }
    }

    @Test void arbitraryCommandsAndExcessiveLimitsAreRejected()
    {
        gateway().withVerifiedSession(server(fingerprint()), password("password"), session -> {
            assertCode(COMMAND_NOT_ALLOWED, () -> session.exec(List.of("sh", "-c", "id"), timeout, 1024));
            assertCode(COMMAND_NOT_ALLOWED, () -> session.exec(List.of("cat", "/proc/loadavg; id"), timeout, 1024));
            assertCode(INVALID_LIMIT, () -> session.exec(List.of("cat", "/proc/loadavg"), Duration.ZERO, 1024));
            assertCode(INVALID_LIMIT, () -> session.exec(List.of("cat", "/proc/loadavg"), timeout, 1024 * 1024 + 1));
            return null;
        });
        assertEquals(0, commands.get());
    }

    @Test void stdoutAndStderrAreBothBounded()
    {
        for (boolean errorStream : new boolean[] {false, true})
        {
            stdout = errorStream ? "" : "x".repeat(4096);
            stderr = errorStream ? "x".repeat(4096) : "";
            assertCode(OUTPUT_LIMIT, () -> gateway().withVerifiedSession(server(fingerprint()), password("password"),
                session -> session.exec(List.of("cat", "/proc/loadavg"), timeout, 64)));
        }
    }

    @Test void slowCommandTimesOut()
    {
        commandDelay = 1000;
        assertCode(COMMAND_TIMEOUT, () -> gateway().withVerifiedSession(server(fingerprint()), password("password"),
            session -> session.exec(List.of("cat", "/proc/loadavg"), Duration.ofMillis(100), 1024)));
    }

    @Test void concurrencySlotsReleaseEvenWhenOperationFails()
    {
        var gateway = new MinaSshGateway(cipher, timeout, timeout, 1);
        assertThrows(IllegalStateException.class, () -> gateway.withVerifiedSession(server(fingerprint()), password("password"), session -> {
            assertCode(CONCURRENCY_LIMIT, () -> gateway.preview("127.0.0.1", sshd.getPort(), timeout));
            throw new IllegalStateException("caller failed");
        }));
        assertNotNull(gateway.preview("127.0.0.1", sshd.getPort(), timeout));
    }

    @Test void slowAuthenticationHasSeparateTimeoutCode()
    {
        sshd.setPasswordAuthenticator((user, password, session) -> {
            try { Thread.sleep(1000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return true;
        });
        var gateway = new MinaSshGateway(cipher, timeout, Duration.ofMillis(100), 8);
        assertCode(AUTH_TIMEOUT, () -> gateway.withVerifiedSession(server(fingerprint()), password("password"), s -> null));
    }

    @Test void sessionCannotEscapeOperationLifetime()
    {
        var escaped = gateway().withVerifiedSession(server(fingerprint()), password("password"), session -> session);
        assertCode(COMMAND_FAILED, () -> escaped.exec(List.of("cat", "/proc/loadavg"), timeout, 1024));
    }

    @Test void corruptCredentialIsNotSentToVerifiedServer()
    {
        var account = new ServerAccountRecord(1L, 1L, 1L, "alice", "PASSWORD", "bad-ciphertext", null,
            true, "/home/alice", null, true, "UNKNOWN", null, null, null, null, null);
        assertCode(CREDENTIAL_INVALID, () -> gateway().withVerifiedSession(server(fingerprint()), account, s -> null));
        assertEquals(0, authentications.get());
    }

    @Test void disabledAndMismatchedAccountsCannotAuthenticate()
    {
        var wrongServer = new ServerRecord(2L, "test", "127.0.0.1", sshd.getPort(), fingerprint(), true, null, null);
        assertCode(ACCOUNT_INVALID, () -> gateway().withVerifiedSession(wrongServer, password("password"), s -> null));
        var disabled = new ServerRecord(1L, "test", "127.0.0.1", sshd.getPort(), fingerprint(), false, null, null);
        assertCode(ACCOUNT_INVALID, () -> gateway().withVerifiedSession(disabled, password("password"), s -> null));
        assertEquals(0, authentications.get());
    }

    private MinaSshGateway gateway() { return new MinaSshGateway(cipher, timeout, timeout, 8); }
    private String fingerprint() { return KeyUtils.getFingerPrint(hostKey.getPublic()); }
    private ServerRecord server(String fingerprint) { return new ServerRecord(1L, "test", "127.0.0.1", sshd.getPort(), fingerprint, true, null, null); }
    private ServerAccountRecord password(String value) { return account("PASSWORD", value, null); }
    private ServerAccountRecord account(String type, String secret, String passphrase)
    {
        return new ServerAccountRecord(1L, 1L, 1L, "alice", type, cipher.encrypt(secret),
            passphrase == null ? null : cipher.encrypt(passphrase), true, "/home/alice", null,
            true, "UNKNOWN", null, null, null, null, null);
    }
    private void assertCode(SshGateway.ErrorCode code, org.junit.jupiter.api.function.Executable action)
    {
        assertEquals(code, assertThrows(SshGateway.SshException.class, action).code());
    }
    private class OutputCommand implements Command
    {
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread worker;
        public void setInputStream(InputStream in) { }
        public void setOutputStream(OutputStream out) { this.out = out; }
        public void setErrorStream(OutputStream err) { this.err = err; }
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        public void start(ChannelSession channel, Environment environment)
        {
            worker = new Thread(() -> {
                try {
                    Thread.sleep(commandDelay);
                    out.write(stdout.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    err.write(stderr.getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(0);
                } catch (IOException | InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            worker.setDaemon(true);
            worker.start();
        }
        public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
    }
}
