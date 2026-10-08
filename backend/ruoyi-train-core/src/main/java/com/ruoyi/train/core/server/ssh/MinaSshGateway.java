package com.ruoyi.train.core.server.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.*;

@Component
public final class MinaSshGateway implements SshGateway
{
    // No user-supplied paths, flags, shell fragments, environment or stdin are accepted.
    private static final Set<List<String>> COMMANDS = Set.of(
        List.of("cat", "/proc/loadavg"), List.of("cat", "/proc/meminfo"), List.of("df", "-Pk"),
        List.of("nvidia-smi", "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version", "--format=csv,noheader,nounits"),
        List.of("nvidia-smi", "--query-compute-apps=gpu_uuid,pid,process_name,used_memory", "--format=csv,noheader,nounits"),
        List.of("systemctl", "--user", "is-system-running"),
        List.of("loginctl", "show-user", "--property=Linger"));

    // DNS lookup may block in native code; cap both caller wait and resolver workers.
    private static final ExecutorService RESOLVER = new ThreadPoolExecutor(0, MAX_SESSIONS,
        30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ssh-host-resolver");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final ServerSecretCipher cipher;
    private final Duration connectTimeout;
    private final Duration authTimeout;
    private final Semaphore slots;

    @Autowired
    public MinaSshGateway(ServerSecretCipher cipher) { this(cipher, DEFAULT_CONNECT_TIMEOUT, DEFAULT_AUTH_TIMEOUT, MAX_SESSIONS); }

    public MinaSshGateway(ServerSecretCipher cipher, Duration connectTimeout, Duration authTimeout, int concurrency)
    {
        this.cipher = Objects.requireNonNull(cipher);
        this.connectTimeout = bounded(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        this.authTimeout = bounded(authTimeout, DEFAULT_AUTH_TIMEOUT);
        if (concurrency < 1 || concurrency > MAX_SESSIONS) throw new SshException(INVALID_LIMIT);
        slots = new Semaphore(concurrency);
    }

    @Override
    public HostKeyPreview preview(String host, int port, Duration timeout)
    {
        validateAddress(host, port);
        Duration limit = bounded(timeout, connectTimeout);
        acquire();
        AtomicReference<HostKeyPreview> observed = new AtomicReference<>();
        // Capture and reject: this connection never accepts an unconfirmed host key.
        try (SshClient client = client((session, address, key) -> {
            observed.set(HostKeyFingerprint.of(key));
            return false;
        }))
        {
            long deadline = deadline(limit);
            try (ClientSession session = connect(client, "host-key-preview", host, port, deadline))
            {
                var state = session.waitFor(EnumSet.of(ClientSessionEvent.CLOSED), remaining(deadline, CONNECT_TIMEOUT));
                if (observed.get() != null) return observed.get();
                throw new SshException(state.contains(ClientSessionEvent.TIMEOUT) ? CONNECT_TIMEOUT : CONNECT_FAILED);
            }
            catch (IOException e)
            {
                if (observed.get() != null) return observed.get();
                throw failure(e, CONNECT_TIMEOUT, CONNECT_FAILED);
            }
        }
        catch (IOException e) { throw failure(e, CONNECT_TIMEOUT, CONNECT_FAILED); }
        finally { slots.release(); }
    }

    @Override
    public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account, Function<SshSession, T> operation)
    {
        validateAccount(server, account);
        Objects.requireNonNull(operation);
        acquire();
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean verified = new AtomicBoolean();
        try (SshClient client = client((session, address, key) -> {
            boolean matches = HostKeyFingerprint.matches(server.hostKeySha256(), key);
            verified.set(matches);
            if (!matches) changed.set(true);
            return matches;
        }))
        {
            long deadline = deadline(connectTimeout);
            try (ClientSession session = connect(client, account.linuxUsername(), server.host(), server.port(), deadline))
            {
                var state = session.waitFor(EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED),
                    remaining(deadline, CONNECT_TIMEOUT));
                if (changed.get()) throw new SshException(HOST_KEY_CHANGED);
                if (state.contains(ClientSessionEvent.TIMEOUT)) throw new SshException(CONNECT_TIMEOUT);
                if (!verified.get() || !state.contains(ClientSessionEvent.WAIT_AUTH) || state.contains(ClientSessionEvent.CLOSED))
                    throw new SshException(CONNECT_FAILED);
                authenticate(session, account);
                try (SshSession limited = new VerifiedSession(session)) { return operation.apply(limited); }
            }
            catch (IOException e)
            {
                if (changed.get()) throw new SshException(HOST_KEY_CHANGED);
                throw failure(e, CONNECT_TIMEOUT, CONNECT_FAILED);
            }
        }
        catch (IOException e) { throw failure(e, CONNECT_TIMEOUT, CONNECT_FAILED); }
        finally { slots.release(); }
    }

    private SshClient client(ServerKeyVerifier verifier)
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
        CoreModuleProperties.IDLE_TIMEOUT.set(client, DEFAULT_COMMAND_TIMEOUT.plusSeconds(5));
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, connectTimeout);
        client.start();
        return client;
    }

    private ClientSession connect(SshClient client, String username, String host, int port, long deadline) throws IOException
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

    private void authenticate(ClientSession session, ServerAccountRecord account)
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

    private void validateAccount(ServerRecord server, ServerAccountRecord account)
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

    private static void validateAddress(String host, int port)
    {
        if (host == null || host.isBlank() || host.length() > 255 || !host.matches("[A-Za-z0-9_.:%-]+") || port < 1 || port > 65535)
            throw new SshException(ACCOUNT_INVALID);
    }

    private void acquire() { if (!slots.tryAcquire()) throw new SshException(CONCURRENCY_LIMIT); }
    private static Duration bounded(Duration value, Duration maximum)
    {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(maximum) > 0)
            throw new SshException(INVALID_LIMIT);
        return value;
    }
    private static long deadline(Duration timeout) { return System.nanoTime() + timeout.toNanos(); }
    private static long remaining(long deadline, ErrorCode code)
    {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SshException(code);
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
    private static SshException failure(Throwable error, ErrorCode timeout, ErrorCode fallback)
    {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) return new SshException(timeout);
        return new SshException(fallback);
    }

    private static final class VerifiedSession implements SshSession
    {
        private final ClientSession session;
        private boolean closed;
        private VerifiedSession(ClientSession session) { this.session = session; }

        @Override public synchronized CommandResult exec(List<String> fixedCommand, Duration timeout, int maxBytes)
        {
            if (closed || !session.isAuthenticated()) throw new SshException(COMMAND_FAILED);
            if (fixedCommand == null) throw new SshException(COMMAND_NOT_ALLOWED);
            List<String> command;
            try { command = List.copyOf(fixedCommand); }
            catch (NullPointerException ignored) { throw new SshException(COMMAND_NOT_ALLOWED); }
            if (!COMMANDS.contains(command)) throw new SshException(COMMAND_NOT_ALLOWED);
            bounded(timeout, DEFAULT_COMMAND_TIMEOUT);
            if (maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES) throw new SshException(INVALID_LIMIT);
            long deadline = deadline(timeout);
            AtomicBoolean overflow = new AtomicBoolean();
            try (ChannelExec channel = session.createExecChannel(String.join(" ", command)))
            {
                var out = new LimitedOutput(maxBytes, overflow, channel);
                var err = new LimitedOutput(maxBytes, overflow, channel);
                channel.setOut(out);
                channel.setErr(err);
                channel.open().verify(remaining(deadline, COMMAND_TIMEOUT));
                var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), remaining(deadline, COMMAND_TIMEOUT));
                if (overflow.get()) throw new SshException(OUTPUT_LIMIT);
                if (events.contains(ClientChannelEvent.TIMEOUT)) throw new SshException(COMMAND_TIMEOUT);
                Integer exit = channel.getExitStatus();
                if (exit == null) throw new SshException(COMMAND_FAILED);
                return new CommandResult(exit, out.text(), err.text());
            }
            catch (IOException e)
            {
                if (overflow.get()) throw new SshException(OUTPUT_LIMIT);
                throw failure(e, COMMAND_TIMEOUT, COMMAND_FAILED);
            }
        }

        @Override public synchronized void close() { closed = true; session.close(true); }
    }

    private static final class LimitedOutput extends OutputStream
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private final AtomicBoolean overflow;
        private final ChannelExec channel;
        private LimitedOutput(int limit, AtomicBoolean overflow, ChannelExec channel)
        { this.limit = limit; this.overflow = overflow; this.channel = channel; }
        @Override public synchronized void write(int value) { write(new byte[] {(byte) value}, 0, 1); }
        @Override public synchronized void write(byte[] value, int offset, int length)
        {
            if (overflow.get()) return;
            if (length > limit - bytes.size()) { overflow.set(true); channel.close(true); return; }
            bytes.write(value, offset, length);
        }
        private synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
