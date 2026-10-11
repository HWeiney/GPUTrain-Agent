package com.ruoyi.train.core.server.proposal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.ssh.MinaSshGateway;
import com.ruoyi.train.core.server.ssh.ServerSecretCipher;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeAccountMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeJobMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeProposalMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeServerMapper;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;

/**
 * 通道 B 集成测试：内嵌 SSHD + 真 MinaSshGateway，验证「创建 → 批准 → 受限 exec →
 * 结果落库」全链路与 CAS/凭证视图语义。
 */
class ProposalExecIntegrationTest
{
    private static final String JOB_UUID = "e53780aa-cad6-4c44-b088-c9a681afe40f";
    private static final String STDOUT = "0.50 0.25 0.10 1/40 100\n";

    private final ServerSecretCipher cipher = new ServerSecretCipher(new byte[32]);
    private final FakeProposalMapper proposals = new FakeProposalMapper();
    private final FakeServerMapper servers = new FakeServerMapper();
    private final FakeAccountMapper accounts = new FakeAccountMapper();
    private final FakeJobMapper jobs = new FakeJobMapper();
    private final AtomicInteger remoteCommands = new AtomicInteger();
    private SshServer sshd;
    private KeyPair hostKey;
    private int remoteExitCode = 0;

    @BeforeEach
    void start() throws Exception
    {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        hostKey = generator.generateKeyPair();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((user, password, session) ->
            "alice".equals(user) && "password".equals(password));
        sshd.setCommandFactory((channel, command) -> {
            remoteCommands.incrementAndGet();
            return new OutputCommand();
        });
        sshd.start();
    }

    @AfterEach
    void stop() throws Exception { sshd.stop(true); }

    @Test
    void approveExecutesProbeOverRealSshAndAttachesResult()
    {
        ProposalService service = service();
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        CommandProposalRecord approved = service.approve(proposalId, 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(0, approved.exitCode());
        assertEquals(STDOUT, approved.stdout());
        assertEquals(1, remoteCommands.get());
    }

    @Test
    void jobInspectProposalRunsRenderedSystemctlCommand()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        ProposalService service = service();
        String proposalId = service.create(1L, 4L, 8L, "job-inspect",
                new ObjectMapper().valueToTree(Map.of("jobId", 72))).proposalId();
        CommandProposalRecord approved = service.approve(proposalId, 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(0, approved.exitCode());
        assertEquals(STDOUT, approved.stdout());
        assertEquals(1, remoteCommands.get());
    }

    @Test
    void secondApproveIsRejectedByCompareAndSet()
    {
        ProposalService service = service();
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        assertEquals("EXECUTED", service.approve(proposalId, 1L, false).status());
        assertEquals("PROPOSAL_NOT_PENDING",
            assertThrows(ServiceException.class, () -> service.approve(proposalId, 1L, false)).getMessage());
        assertEquals(1, remoteCommands.get(), "命令只允许执行一次");
        assertEquals(0, proposals.findById(proposalId).exitCode());
    }

    @Test
    void rejectedProposalNeverReachesRemoteCommand()
    {
        ProposalService service = service();
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        assertEquals("REJECTED", service.reject(proposalId, 1L, false).status());
        assertEquals(0, remoteCommands.get());
        assertEquals("PROPOSAL_NOT_PENDING",
            assertThrows(ServiceException.class, () -> service.reject(proposalId, 1L, false)).getMessage());
    }

    @Test
    void nonZeroExitCodeIsObservableExecutedResult()
    {
        remoteExitCode = 1;
        ProposalService service = service();
        String proposalId = service.create(1L, 4L, 8L, "probe-systemd", null).proposalId();
        CommandProposalRecord approved = service.approve(proposalId, 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(1, approved.exitCode());
        assertTrue(proposals.failedCodes.isEmpty());
    }

    @Test
    void authenticationFailureMarksProposalFailed()
    {
        ProposalService service = service();
        // 凭证口令错误：创建仍通过（脱敏视图只校验归属/可用性），批准时认证失败
        accounts.record = account("wrong-password");
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        CommandProposalRecord failed = service.approve(proposalId, 1L, false);
        assertEquals("FAILED", failed.status());
        assertEquals("AUTH_FAILED", failed.errorCode());
        assertEquals(0, remoteCommands.get());
    }

    @Test
    void createRejectsForeignAccountBeforeAnyConnection()
    {
        ProposalService service = service();
        accounts.record = ProposalTestFakes.account(8L, 4L, 2L, null, true);
        assertEquals("SERVER_ACCOUNT_UNAVAILABLE",
            assertThrows(ServiceException.class, () -> service.create(1L, 4L, 8L, "probe-loadavg", null)).getMessage());
        assertEquals(0, remoteCommands.get());
        assertTrue(proposals.rows.isEmpty());
    }

    private ProposalService service()
    {
        servers.record = server();
        accounts.record = account("password");
        return new ProposalService(proposals, ProposalTestFakes.registry(jobs), servers, accounts,
                new MinaSshGateway(cipher, Duration.ofSeconds(3), Duration.ofSeconds(3), 8),
                new ObjectMapper());
    }

    private ServerRecord server()
    {
        return new ServerRecord(4L, "test", "127.0.0.1", sshd.getPort(),
                KeyUtils.getFingerPrint(hostKey.getPublic()), true, Instant.now(), Instant.now());
    }

    private ServerAccountRecord account(String password)
    {
        return new ServerAccountRecord(8L, 4L, 1L, "alice", "PASSWORD", cipher.encrypt(password),
                null, true, "/home/alice/training", "{}", true, "ONLINE", null, null, null,
                Instant.now(), Instant.now());
    }

    private class OutputCommand implements Command
    {
        private OutputStream out;
        private ExitCallback callback;
        private Thread worker;
        public void setInputStream(InputStream in) { }
        public void setOutputStream(OutputStream out) { this.out = out; }
        public void setErrorStream(OutputStream err) { }
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        public void start(ChannelSession channel, Environment environment)
        {
            worker = new Thread(() -> {
                try
                {
                    out.write(STDOUT.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    callback.onExit(remoteExitCode);
                }
                catch (IOException ignored) { Thread.currentThread().interrupt(); }
            });
            worker.setDaemon(true);
            worker.start();
        }
        public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
    }
}
