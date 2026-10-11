package com.ruoyi.train.core.server.ssh;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.*;

/**
 * 共享的 MINA SSHD 传输层：客户端构建、DNS 预解析连接、凭证解密认证与账号校验。
 *
 * exec 网关（短命令会话）与 Web 终端（长交互会话）复用同一套传输语义，
 * idle 超时由调用方按会话类型参数化：exec 用 {@link #COMMAND_IDLE_TIMEOUT}，
 * 终端用自身的会话治理参数（心跳保活，远长于单命令）。
 */
@Component
public final class SshTransport
{
    /** exec 短命令连接的 idle 超时（单命令上限 15s + 余量）。 */
    static final Duration COMMAND_IDLE_TIMEOUT = SshGateway.DEFAULT_COMMAND_TIMEOUT.plusSeconds(5);

    // DNS lookup may block in native code; cap both caller wait and resolver workers.
    private static final ExecutorService RESOLVER = new ThreadPoolExecutor(0, SshGateway.MAX_SESSIONS,
        30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ssh-host-resolver");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private final ServerSecretCipher cipher;
    private final Duration connectTimeout;
    private final Duration authTimeout;

    @Autowired
    public SshTransport(ServerSecretCipher cipher)
    {
        this(cipher, SshGateway.DEFAULT_CONNECT_TIMEOUT, SshGateway.DEFAULT_AUTH_TIMEOUT);
    }

    public SshTransport(ServerSecretCipher cipher, Duration connectTimeout, Duration authTimeout)
    {
        this.cipher = Objects.requireNonNull(cipher);
        this.connectTimeout = bounded(connectTimeout, SshGateway.DEFAULT_CONNECT_TIMEOUT);
        this.authTimeout = bounded(authTimeout, SshGateway.DEFAULT_AUTH_TIMEOUT);
    }

    /** 构建已启动的 SshClient：不读任何本地配置/密钥/agent，只认传入的 verifier。 */
    public SshClient client(ServerKeyVerifier verifier, Duration idleTimeout)
    {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(verifier);
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
        client.setAgentFactory(null);
        client.setUserInteraction(null);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE, UserAuthPublicKeyFactory.INSTANCE));
        CoreModuleProperties.NIO_WORKERS.set(client, 1);
        CoreModuleProperties.AUTH_TIMEOUT.set(client, connectTimeout.plus(authTimeout));
        CoreModuleProperties.IDLE_TIMEOUT.set(client, idleTimeout);
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, connectTimeout);
        client.start();
        return client;
    }

    /** DNS 预解析后建立连接；解析与连接共用 deadline 预算。 */
    public ClientSession connect(SshClient client, String username, String host, int port, long deadline) throws IOException
    {
        Future<InetAddress> lookup;
        try { lookup = RESOLVER.submit(() -> InetAddress.getByName(host)); }
        catch (RejectedExecutionException ignored) { throw new SshException(CONCURRENCY_LIMIT); }
        InetAddress address;
        try { address = lookup.get(remaining(deadline, CONNECT_TIMEOUT), TimeUnit.MILLISECONDS); }
        catch (TimeoutException ignored) { throw new SshException(CONNECT_TIMEOUT); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new SshException(CONNECT_FAILED); }
        catch (ExecutionException ignored) { throw new SshException(CONNECT_FAILED); }
        finally { lookup.cancel(true); }
        return client.connect(username, new InetSocketAddress(address, port))
            .verify(remaining(deadline, CONNECT_TIMEOUT)).getSession();
    }

    /** 解密凭证并完成密码/私钥认证。 */
    public void authenticate(ClientSession session, ServerAccountRecord account)
    {
        long deadline = deadline(authTimeout);
        try
        {
            String credential = cipher.decrypt(account.credentialCiphertext());
            if ("PASSWORD".equals(account.authType()))
            {
                session.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
                session.addPasswordIdentity(credential);
            }
            else
            {
                session.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
                String passphrase = account.passphraseCiphertext() == null ? null : cipher.decrypt(account.passphraseCiphertext());
                var keys = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(session,
                    NamedResource.ofName("stored-ssh-identity"),
                    passphrase == null ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(passphrase), credential);
                if (keys == null || keys.size() != 1) throw new SshException(CREDENTIAL_INVALID);
                session.addPublicKeyIdentity(keys.iterator().next());
            }
            session.auth().verify(remaining(deadline, AUTH_TIMEOUT));
        }
        catch (SshException e) { throw e; }
        catch (Exception e) { throw failure(e, AUTH_TIMEOUT, AUTH_FAILED); }
    }

    /** 服务器与账号的可用性/归属/主机密钥校验，任何连接建立前必须通过。 */
    public void validateAccount(ServerRecord server, ServerAccountRecord account)
    {
        if (server == null || account == null || !server.enabled() || !account.enabled()
            || server.serverId() == null || !server.serverId().equals(account.serverId())
            || !account.hasCredential() || account.credentialCiphertext() == null
            || account.linuxUsername() == null || !account.linuxUsername().matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}[$]?" )
            || !("PASSWORD".equals(account.authType()) || "PRIVATE_KEY".equals(account.authType())))
            throw new SshException(ACCOUNT_INVALID);
        validateAddress(server.host(), server.port());
        if (!HostKeyFingerprint.isValid(server.hostKeySha256())) throw new SshException(HOST_KEY_UNCONFIRMED);
    }

    public Duration connectTimeout() { return connectTimeout; }

    static void validateAddress(String host, int port)
    {
        if (host == null || host.isBlank() || host.length() > 255 || !host.matches("[A-Za-z0-9_.:%-]+") || port < 1 || port > 65535)
            throw new SshException(ACCOUNT_INVALID);
    }

    static Duration bounded(Duration value, Duration maximum)
    {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(maximum) > 0)
            throw new SshException(INVALID_LIMIT);
        return value;
    }

    /** 终端等长会话调用方也需要的 deadline 工具，公开为包外可用。 */
    public static long deadline(Duration timeout) { return System.nanoTime() + timeout.toNanos(); }

    public static long remaining(long deadline, ErrorCode code)
    {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SshException(code);
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    static SshException failure(Throwable error, ErrorCode timeout, ErrorCode fallback)
    {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) return new SshException(timeout);
        return new SshException(fallback);
    }
}
