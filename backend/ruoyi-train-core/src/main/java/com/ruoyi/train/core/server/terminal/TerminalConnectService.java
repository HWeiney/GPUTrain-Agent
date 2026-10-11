package com.ruoyi.train.core.server.terminal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.ssh.HostKeyFingerprint;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;
import com.ruoyi.train.core.server.ssh.SshTransport;
import com.ruoyi.train.core.server.terminal.TerminalTicketService.Grant;
import com.ruoyi.train.core.server.terminal.domain.TerminalSessionRecord;
import com.ruoyi.train.core.server.terminal.mapper.TerminalAuditMapper;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.CONNECT_TIMEOUT;

/**
 * 终端建连：消费票据授权后，按 exec 网关相同的「捕获并校验主机密钥」语义
 * 建立 SSH 连接并打开 PTY shell 通道。
 *
 * 安全要点：
 *  - 票据授权在拦截器消费，这里对 grant 做归属与可用性二次校验（签发到握手之间状态可能变化）
 *  - 主机密钥不匹配立即断开且零认证（与 MinaSshGateway 一致的语义）
 *  - 建连失败必须归还会话配额
 */
@Service
public class TerminalConnectService
{
    private static final String TERMINAL_TERM = "xterm-256color";

    private final TerminalSessionManager manager;
    private final TerminalProperties properties;
    private final SshTransport transport;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final TerminalAuditMapper audit;

    public TerminalConnectService(TerminalSessionManager manager, TerminalProperties properties,
        SshTransport transport, ServerMapper servers, ServerAccountMapper accounts, TerminalAuditMapper audit)
    {
        this.manager = manager;
        this.properties = properties;
        this.transport = transport;
        this.servers = servers;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * 建立终端会话：审计落行（每次尝试都记录）→ 配额预占 → 二次校验 → SSH 连接认证 → PTY shell 通道。
     * 失败路径：归还配额并把审计行置为 ERROR。
     */
    public TerminalSession open(Grant grant, WebSocketSession ws, int cols, int rows)
    {
        String sessionUuid = UUID.randomUUID().toString();
        recordAttempt(sessionUuid, grant);
        try
        {
            manager.reserve(grant.userId());
            try
            {
                return connect(grant, ws, cols, rows, sessionUuid);
            }
            catch (RuntimeException e)
            {
                manager.release(grant.userId());
                throw e;
            }
        }
        catch (TerminalException e)
        {
            markError(sessionUuid, e.code());
            throw e;
        }
        catch (SshException e)
        {
            markError(sessionUuid, e.code().name());
            throw new TerminalException(e.code().name(), e);
        }
        catch (RuntimeException e)
        {
            markError(sessionUuid, "INTERNAL");
            throw e;
        }
    }

    /** 审计落行：尝试即记录；审计失败不阻断终端主流程。 */
    private void recordAttempt(String sessionUuid, Grant grant)
    {
        try
        {
            audit.insertAttempt(new TerminalSessionRecord(sessionUuid, grant.userId(), grant.serverId(),
                grant.accountId(), "ACTIVE", null, null, Instant.now(), null));
        }
        catch (Exception ignored) { }
    }

    private void markError(String sessionUuid, String errorCode)
    {
        try { audit.markError(sessionUuid, errorCode, Instant.now()); } catch (Exception ignored) { }
    }

    private TerminalSession connect(Grant grant, WebSocketSession ws, int cols, int rows, String sessionUuid)
    {
        ServerRecord server = servers.findById(grant.serverId());
        // 带机密视图：后续认证需要解密 ciphertext（findById 是脱敏视图，ciphertext 恒为 NULL）
        ServerAccountRecord account = accounts.findCredentialsByAuthorizedAccountId(grant.accountId());
        // 与 TerminalController 签发时相同的归属与可用性语义（二次校验）
        if (server == null || !server.enabled() || account == null || !grant.userId().equals(account.ownerUserId())
            || !grant.serverId().equals(account.serverId()) || !account.enabled() || !account.hasCredential())
            throw new TerminalException("ACCOUNT_INVALID");
        transport.validateAccount(server, account);

        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean verified = new AtomicBoolean();
        // 与 exec 网关相同的「捕获并校验」主机密钥语义：不匹配立即断开且零认证
        SshClient client = transport.client((session, address, key) -> {
            boolean matches = HostKeyFingerprint.matches(server.hostKeySha256(), key);
            verified.set(matches);
            if (!matches) changed.set(true);
            return matches;
        }, properties.idleTimeout().plus(Duration.ofMinutes(1)));
        try
        {
            long deadline = SshTransport.deadline(transport.connectTimeout());
            ClientSession session = transport.connect(client, account.linuxUsername(), server.host(), server.port(), deadline);
            try
            {
                var state = session.waitFor(EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED),
                    SshTransport.remaining(deadline, CONNECT_TIMEOUT));
                if (changed.get()) throw new TerminalException("HOST_KEY_CHANGED");
                if (state.contains(ClientSessionEvent.TIMEOUT)) throw new TerminalException("CONNECT_TIMEOUT");
                if (!verified.get() || !state.contains(ClientSessionEvent.WAIT_AUTH) || state.contains(ClientSessionEvent.CLOSED))
                    throw new TerminalException("CONNECT_FAILED");
                transport.authenticate(session, account);
                ChannelShell channel = createShell(session, cols, rows);
                // 先安装输出 sink 再打开通道：服务端 shell 可能在 open().verify() 返回前就开始写输出
                TerminalSession terminal = new TerminalSession(sessionUuid, grant.userId(), grant.serverId(),
                    grant.accountId(), manager, audit, client, session, channel, ws);
                channel.open().verify(SshTransport.remaining(SshTransport.deadline(transport.connectTimeout()), CONNECT_TIMEOUT));
                manager.register(terminal);
                return terminal;
            }
            catch (Exception e)
            {
                session.close(true);
                throw e;
            }
        }
        catch (Exception e)
        {
            try { client.close(true); } catch (Exception ignored) { }
            if (e instanceof TerminalException terminal) throw terminal;
            if (e instanceof SshException ssh) throw new TerminalException(ssh.code().name(), ssh);
            if (e instanceof IOException) throw new TerminalException("CONNECT_FAILED", e);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new TerminalException("INTERNAL", e);
        }
    }

    /** 只创建通道（含 PTY 配置），不打开：调用方需在安装输出 sink 之后再 open。 */
    private ChannelShell createShell(ClientSession session, int cols, int rows) throws IOException
    {
        PtyChannelConfiguration pty = new PtyChannelConfiguration();
        pty.setPtyType(TERMINAL_TERM);
        pty.setPtyColumns(cols);
        pty.setPtyLines(rows);
        return session.createShellChannel(pty, Map.of());
    }
}
