package com.ruoyi.train.core.server.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.ssh.ServerSecretCipher;
import com.ruoyi.train.core.server.ssh.SshTransport;
import com.ruoyi.train.core.server.terminal.TerminalTicketService.Grant;
import com.ruoyi.train.core.server.terminal.domain.TerminalSessionRecord;
import com.ruoyi.train.core.server.terminal.mapper.TerminalAuditMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端通道端到端集成测试：内嵌 SSHD 提供 echo shell，Fake WebSocket 会话
 * 承载前端一侧，验证 建连 → started → 输入回显 → 关闭回收 → 配额治理 全链路。
 */
class TerminalShellChannelIntegrationTest
{
    private final ServerSecretCipher cipher = new ServerSecretCipher(new byte[32]);
    private final FakeServerMapper servers = new FakeServerMapper();
    private final FakeAccountMapper accounts = new FakeAccountMapper();
    private final FakeTerminalAuditMapper audit = new FakeTerminalAuditMapper();
    private SshServer sshd;
    private KeyPair hostKey;
    private TerminalSessionManager manager;
    private TerminalConnectService connectService;
    private TerminalProperties properties;

    @BeforeEach void start() throws Exception
    {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        hostKey = generator.generateKeyPair();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((user, password, session) -> "alice".equals(user) && "password".equals(password));
        sshd.setShellFactory(channel -> new EchoShell());
        sshd.start();

        properties = new TerminalProperties(Duration.ofMinutes(30), Duration.ofHours(12), 3, 16, Duration.ofSeconds(60));
        manager = new TerminalSessionManager(properties);
        connectService = new TerminalConnectService(manager, properties, new SshTransport(cipher),
            servers, accounts, audit);
        var fingerprint = KeyUtils.getFingerPrint(hostKey.getPublic());
        servers.record = new ServerRecord(1L, "test", "127.0.0.1", sshd.getPort(), fingerprint, true, null, null);
        accounts.record = passwordAccount();
    }

    @AfterEach void stop() throws Exception
    {
        if (manager != null) manager.sessions().forEach(TerminalSession::close);
        sshd.stop(true);
    }

    @Test void openStartedInputEchoCloseAndQuotaRecycle() throws Exception
    {
        var ws = new FakeWebSocketSession();
        TerminalSession terminal = connectService.open(new Grant(1L, 1L, 1L), ws, 120, 40);
        terminal.sendStarted();
        assertTrue(terminal.isOpen());
        assertEquals(1, manager.sessions().size());

        // started 信令（TEXT）
        assertTrue(firstText(ws).contains("\"type\":\"started\""), firstText(ws));

        // 键盘输入经 SSH 通道被 echo shell 回显为 BINARY 帧
        terminal.handleInput("ping-terminal\n".getBytes(StandardCharsets.UTF_8));
        String output = awaitBinaryOutput(ws, Duration.ofSeconds(10));
        assertTrue(output.contains("SHELL-READY"), output);
        assertTrue(output.contains("ping-terminal"), output);

        // 关闭幂等且级联回收
        terminal.close("SSH_CLOSED");
        terminal.close("SSH_CLOSED");
        assertFalse(terminal.isOpen());
        assertTrue(manager.sessions().isEmpty());

        // 审计三态：尝试落行 ACTIVE，关闭置 CLOSED 且幂等（重复 close 不重复落审计）
        assertEquals(1, audit.inserted.size());
        assertEquals("ACTIVE", audit.inserted.get(0).status());
        assertEquals(1L, audit.inserted.get(0).actorUserId());
        assertEquals(List.of("SSH_CLOSED"), audit.finished);

        // 配额已归还：可再次建连
        var second = connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24);
        second.close("SSH_CLOSED");
        assertEquals(2, audit.inserted.size());
        assertEquals(2, audit.finished.size());
    }

    @Test void perUserSessionLimitIsEnforcedAndReleased() throws Exception
    {
        properties = new TerminalProperties(Duration.ofMinutes(30), Duration.ofHours(12), 1, 16, Duration.ofSeconds(60));
        manager = new TerminalSessionManager(properties);
        connectService = new TerminalConnectService(manager, properties, new SshTransport(cipher), servers, accounts, audit);
        TerminalSession first = connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24);
        var limited = assertThrows(TerminalException.class,
            () -> connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24));
        assertEquals("SESSION_LIMIT", limited.code());
        first.close("SSH_CLOSED");
        assertNotNull(connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24));
    }

    @Test void unavailableAccountIsRejectedWithoutQuotaLeak() throws Exception
    {
        accounts.record = null;
        var rejected = assertThrows(TerminalException.class,
            () -> connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24));
        assertEquals("ACCOUNT_INVALID", rejected.code());
        assertTrue(manager.sessions().isEmpty());
        // 失败路径已审计为 ERROR
        assertEquals(List.of("ACCOUNT_INVALID"), audit.errors);
        // 失败路径配额已归还：有效账号仍可建连
        accounts.record = passwordAccount();
        assertNotNull(connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24));
    }

    @Test void hostKeyRotationIsRejectedBeforeAuthentication()
    {
        servers.record = new ServerRecord(1L, "test", "127.0.0.1", sshd.getPort(),
            "SHA256:" + "A".repeat(43), true, null, null);
        var rejected = assertThrows(TerminalException.class,
            () -> connectService.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24));
        assertEquals("HOST_KEY_CHANGED", rejected.code());
    }

    @Test void idleSweepClosesExpiredSessions() throws InterruptedException
    {
        // 用短 idle 窗口构造独立管理器，验证巡检语义而非真实 30 分钟等待
        var shortIdle = new TerminalProperties(Duration.ofMillis(200), Duration.ofHours(12), 3, 16, Duration.ofSeconds(60));
        var shortManager = new TerminalSessionManager(shortIdle);
        var shortConnect = new TerminalConnectService(shortManager, shortIdle, new SshTransport(cipher), servers, accounts, audit);
        TerminalSession terminal = shortConnect.open(new Grant(1L, 1L, 1L), new FakeWebSocketSession(), 80, 24);
        assertTrue(terminal.isOpen());
        awaitCondition(() -> terminal.idleExpired(shortIdle.idleTimeout()), Duration.ofSeconds(5));
        shortManager.sweep();
        assertFalse(terminal.isOpen());
        assertTrue(shortManager.sessions().isEmpty());
    }

    private ServerAccountRecord passwordAccount()
    {
        return new ServerAccountRecord(1L, 1L, 1L, "alice", "PASSWORD", cipher.encrypt("password"), null,
            true, "/home/alice", null, true, "UNKNOWN", null, null, null, null, null);
    }

    private static String firstText(FakeWebSocketSession ws)
    {
        return ws.sent.stream()
            .filter(message -> message instanceof TextMessage)
            .map(message -> ((TextMessage) message).getPayload())
            .findFirst().orElse("");
    }

    /** 轮询汇聚 BINARY 帧输出，直到包含足够字节或超时。payload 须 duplicate 以免消费共享 buffer 的 position。 */
    private static String awaitBinaryOutput(FakeWebSocketSession ws, Duration timeout) throws InterruptedException
    {
        StringBuilder text = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline)
        {
            text.setLength(0);
            ws.sent.stream()
                .filter(message -> message instanceof BinaryMessage)
                .forEach(message -> {
                    var buffer = ((BinaryMessage) message).getPayload().duplicate();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    text.append(new String(bytes, StandardCharsets.UTF_8));
                });
            if (text.indexOf("ping-terminal") >= 0) return text.toString();
            Thread.sleep(50);
        }
        return text.toString();
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline)
        {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    /** 简单 echo shell：启动横幅 + 回显 stdin，模拟 PTY 行为。 */
    private static final class EchoShell implements Command
    {
        private InputStream in;
        private OutputStream out;
        private Thread worker;

        public void setInputStream(InputStream in) { this.in = in; }
        public void setOutputStream(OutputStream out) { this.out = out; }
        public void setErrorStream(OutputStream err) { }
        public void setExitCallback(ExitCallback callback) { }
        public void start(ChannelSession channel, Environment environment)
        {
            worker = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try
                {
                    out.write("SHELL-READY\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    int read;
                    while ((read = in.read(buffer)) >= 0)
                    {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
                catch (IOException ignored) { }
            });
            worker.setDaemon(true);
            worker.start();
        }
        public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
    }

    /** 手写 fake：记录服务端发出的消息，供断言 started/echo/closed。 */
    static final class FakeWebSocketSession implements WebSocketSession
    {
        private final Map<String, Object> attributes = new HashMap<>();
        final List<WebSocketMessage<?>> sent = new CopyOnWriteArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);

        public String getId() { return "fake-terminal"; }
        public URI getUri() { return null; }
        public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        public Map<String, Object> getAttributes() { return attributes; }
        public Principal getPrincipal() { return null; }
        public java.net.InetSocketAddress getLocalAddress() { return null; }
        public java.net.InetSocketAddress getRemoteAddress() { return null; }
        public String getProtocol() { return null; }
        public List<WebSocketExtension> getExtensions() { return List.of(); }
        public String getAcceptedProtocol() { return null; }
        public void setTextMessageSizeLimit(int limit) { }
        public int getTextMessageSizeLimit() { return 8192; }
        public void setBinaryMessageSizeLimit(int limit) { }
        public int getBinaryMessageSizeLimit() { return 8192; }
        public int getMaxBinaryMessageBufferSize() { return 8192; }
        public void setMaxBinaryMessageBufferSize(int bufferSize) { }
        public int getMaxTextMessageBufferSize() { return 8192; }
        public void setMaxTextMessageBufferSize(int bufferSize) { }
        public boolean isOpen() { return open.get(); }
        public void sendMessage(WebSocketMessage<?> message) throws IOException
        {
            if (!open.get()) throw new IOException("session closed");
            sent.add(message);
        }
        public void close() throws IOException { open.set(false); }
        public void close(CloseStatus status) throws IOException { open.set(false); }
    }

    private static final class FakeServerMapper implements ServerMapper
    {
        ServerRecord record;
        public int insert(ServerRecord server) { return 0; }
        public ServerRecord findById(Long serverId) { return record; }
        public List<ServerRecord> listForOwner(Long ownerUserId, boolean isAdmin) { return List.of(); }
        public int setEnabled(Long serverId, boolean enabled, Instant updatedAt) { return 0; }
    }

    private static final class FakeAccountMapper implements ServerAccountMapper
    {
        ServerAccountRecord record;
        public int insert(ServerAccountRecord account) { return 0; }
        /** 模拟真实 publicColumns 语义：无 ciphertext，仅可见性标志。 */
        public ServerAccountRecord findById(Long accountId) {
            if (record == null) return null;
            return new ServerAccountRecord(record.accountId(), record.serverId(), record.ownerUserId(),
                record.linuxUsername(), record.authType(), null, null, record.hasCredential(),
                record.allowedRoot(), record.profileJson(), record.enabled(), record.status(),
                record.errorCode(), record.lastProbeAt(), record.lastSuccessAt(), record.createdAt(), record.updatedAt());
        }
        public List<ServerAccountRecord> listForOwner(Long serverId, Long ownerUserId) { return List.of(); }
        public ServerAccountRecord findCredentialsByAuthorizedAccountId(Long authorizedAccountId) { return record; }
        public List<ServerAccountRecord> listEnabledForProbe(Long afterAccountId, int limit) { return List.of(); }
        public int updateCredentials(Long authorizedAccountId, String credentialCiphertext,
            String passphraseCiphertext, Instant updatedAt) { return 0; }
        public int updateProbeOutcome(Long accountId, String status, String errorCode,
            Instant sampledAt, boolean successful) { return 0; }
    }

    /** 手写 fake：记录审计调用序列，供三态断言。 */
    private static final class FakeTerminalAuditMapper implements TerminalAuditMapper
    {
        final List<TerminalSessionRecord> inserted = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> finished = new CopyOnWriteArrayList<>();

        public int insertAttempt(TerminalSessionRecord record) { inserted.add(record); return 1; }
        public int markError(String sessionUuid, String errorCode, Instant closedAt) { errors.add(errorCode); return 1; }
        public int finishAttempt(String sessionUuid, String closeReason, Instant closedAt) { finished.add(closeReason); return 1; }
    }
}
