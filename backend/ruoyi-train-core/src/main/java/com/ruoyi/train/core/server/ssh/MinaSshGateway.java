package com.ruoyi.train.core.server.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.*;

/**
 * exec 短命令网关：连接/认证/校验委托 {@link SshTransport}，自身只保留
 * 并发槽与命令白名单语义。交互式长会话（Web 终端）不走本网关。
 */
@Component
public final class MinaSshGateway implements SshGateway
{
    // No user-supplied paths, flags, shell fragments, environment or stdin are accepted.
    private static final Set<List<String>> COMMANDS = Set.of(
        List.of("cat", "/proc/loadavg"), List.of("cat", "/proc/meminfo"), List.of("df", "-Pk"),
        List.of("nvidia-smi", "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version", "--format=csv,noheader,nounits"),
        List.of("nvidia-smi", "--query-compute-apps=gpu_uuid,pid,process_name,used_memory", "--format=csv,noheader,nounits"),
        List.of("systemctl", "--user", "is-system-running"),
        List.of("systemd-run", "--user", "--wait", "--collect", "--unit=gputrain-capability-probe.service",
            "--property=StandardOutput=append:/dev/null", "--property=StandardError=append:/dev/null", "/usr/bin/true"));
    private static final String SYSTEMD_PROPERTIES = "LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup";

    private final SshTransport transport;
    private final Semaphore slots;

    @Autowired
    public MinaSshGateway(SshTransport transport) { this(transport, MAX_SESSIONS); }

    public MinaSshGateway(ServerSecretCipher cipher, Duration connectTimeout, Duration authTimeout, int concurrency)
    {
        this(new SshTransport(cipher, connectTimeout, authTimeout), concurrency);
    }

    MinaSshGateway(SshTransport transport, int concurrency)
    {
        this.transport = Objects.requireNonNull(transport);
        if (concurrency < 1 || concurrency > MAX_SESSIONS) throw new SshException(INVALID_LIMIT);
        slots = new Semaphore(concurrency);
    }

    @Override
    public HostKeyPreview preview(String host, int port, Duration timeout)
    {
        SshTransport.validateAddress(host, port);
        Duration limit = SshTransport.bounded(timeout, transport.connectTimeout());
        acquire();
        AtomicReference<HostKeyPreview> observed = new AtomicReference<>();
        // Capture and reject: this connection never accepts an unconfirmed host key.
        try (SshClient client = transport.client((session, address, key) -> {
            observed.set(HostKeyFingerprint.of(key));
            return false;
        }, SshTransport.COMMAND_IDLE_TIMEOUT))
        {
            long deadline = SshTransport.deadline(limit);
            try (ClientSession session = transport.connect(client, "host-key-preview", host, port, deadline))
            {
                var state = session.waitFor(EnumSet.of(ClientSessionEvent.CLOSED), SshTransport.remaining(deadline, CONNECT_TIMEOUT));
                if (observed.get() != null) return observed.get();
                throw new SshException(state.contains(ClientSessionEvent.TIMEOUT) ? CONNECT_TIMEOUT : CONNECT_FAILED);
            }
            catch (IOException e)
            {
                if (observed.get() != null) return observed.get();
                throw SshTransport.failure(e, CONNECT_TIMEOUT, CONNECT_FAILED);
            }
        }
        catch (IOException e) { throw SshTransport.failure(e, CONNECT_TIMEOUT, CONNECT_FAILED); }
        finally { slots.release(); }
    }

    @Override
    public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account, Function<SshSession, T> operation)
    {
        transport.validateAccount(server, account);
        Objects.requireNonNull(operation);
        acquire();
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean verified = new AtomicBoolean();
        try (SshClient client = transport.client((session, address, key) -> {
            boolean matches = HostKeyFingerprint.matches(server.hostKeySha256(), key);
            verified.set(matches);
            if (!matches) changed.set(true);
            return matches;
        }, SshTransport.COMMAND_IDLE_TIMEOUT))
        {
            long deadline = SshTransport.deadline(transport.connectTimeout());
            try (ClientSession session = transport.connect(client, account.linuxUsername(), server.host(), server.port(), deadline))
            {
                var state = session.waitFor(EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED),
                    SshTransport.remaining(deadline, CONNECT_TIMEOUT));
                if (changed.get()) throw new SshException(HOST_KEY_CHANGED);
                if (state.contains(ClientSessionEvent.TIMEOUT)) throw new SshException(CONNECT_TIMEOUT);
                if (!verified.get() || !state.contains(ClientSessionEvent.WAIT_AUTH) || state.contains(ClientSessionEvent.CLOSED))
                    throw new SshException(CONNECT_FAILED);
                transport.authenticate(session, account);
                try (SshSession limited = new VerifiedSession(session)) { return operation.apply(limited); }
            }
            catch (IOException e)
            {
                if (changed.get()) throw new SshException(HOST_KEY_CHANGED);
                throw SshTransport.failure(e, CONNECT_TIMEOUT, CONNECT_FAILED);
            }
        }
        catch (IOException e) { throw SshTransport.failure(e, CONNECT_TIMEOUT, CONNECT_FAILED); }
        finally { slots.release(); }
    }

    private void acquire() { if (!slots.tryAcquire()) throw new SshException(CONCURRENCY_LIMIT); }

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
            if (!allowedCommand(command)) throw new SshException(COMMAND_NOT_ALLOWED);
            SshTransport.bounded(timeout, DEFAULT_COMMAND_TIMEOUT);
            if (maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES) throw new SshException(INVALID_LIMIT);
            long deadline = SshTransport.deadline(timeout);
            AtomicBoolean overflow = new AtomicBoolean();
            String commandLine = command.stream().map(MinaSshGateway::shellQuote)
                    .collect(java.util.stream.Collectors.joining(" "));
            try (ChannelExec channel = session.createExecChannel(commandLine))
            {
                var out = new LimitedOutput(maxBytes, overflow, channel);
                var err = new LimitedOutput(maxBytes, overflow, channel);
                channel.setOut(out);
                channel.setErr(err);
                channel.open().verify(SshTransport.remaining(deadline, COMMAND_TIMEOUT));
                var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), SshTransport.remaining(deadline, COMMAND_TIMEOUT));
                if (overflow.get()) throw new SshException(OUTPUT_LIMIT);
                if (events.contains(ClientChannelEvent.TIMEOUT)) throw new SshException(COMMAND_TIMEOUT);
                Integer exit = channel.getExitStatus();
                if (exit == null) throw new SshException(COMMAND_FAILED);
                return new CommandResult(exit, out.text(), err.text());
            }
            catch (IOException e)
            {
                if (overflow.get()) throw new SshException(OUTPUT_LIMIT);
                throw SshTransport.failure(e, COMMAND_TIMEOUT, COMMAND_FAILED);
            }
        }

        @Override public synchronized void uploadPrivateFile(String allowedRoot, String privateDirectory,
                String fileName, byte[] content, int maxBytes)
        {
            if (closed || !session.isAuthenticated() || content == null || content.length > maxBytes
                    || maxBytes < 1 || maxBytes > 65536 || fileName == null
                    || !fileName.matches("[A-Za-z0-9._-]{1,128}")) throw new SshException(INVALID_LIMIT);
            Path root = absoluteNormalized(allowedRoot);
            Path directory = absoluteNormalized(privateDirectory);
            if (directory.equals(root) || !directory.startsWith(root)) throw new SshException(COMMAND_NOT_ALLOWED);
            String remoteFile = directory.resolve(fileName).toString();
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session))
            {
                String canonicalRoot = sftp.canonicalPath(root.toString());
                if (!root.toString().equals(canonicalRoot)) throw new SshException(COMMAND_NOT_ALLOWED);
                Path current = root;
                for (Path segment : root.relativize(directory))
                {
                    current = current.resolve(segment);
                    SftpClient.Attributes attributes = lstatOrNull(sftp, current.toString());
                    if (attributes == null)
                    {
                        try { sftp.mkdir(current.toString()); }
                        catch (SftpException race)
                        {
                            if (race.getStatus() != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS) throw race;
                        }
                        attributes = sftp.lstat(current.toString());
                    }
                    if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new SshException(COMMAND_NOT_ALLOWED);
                    sftp.setStat(current.toString(), new SftpClient.Attributes().perms(0700));
                }
                SftpClient.Attributes existing = lstatOrNull(sftp, remoteFile);
                if (existing != null && (!existing.isRegularFile() || existing.isSymbolicLink()))
                    throw new SshException(COMMAND_NOT_ALLOWED);
                try (OutputStream output = sftp.write(remoteFile, SftpClient.OpenMode.Create,
                        SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate))
                {
                    output.write(content);
                }
                sftp.setStat(remoteFile.toString(), new SftpClient.Attributes().perms(0600));
            }
            catch (SshException e) { throw e; }
            catch (IOException e) { throw new SshException(COMMAND_FAILED); }
        }

        @Override public synchronized byte[] readPrivateFile(String allowedRoot, String privateDirectory,
                String fileName, long offset, int maxBytes)
        {
            if (closed || !session.isAuthenticated() || offset < 0 || maxBytes < 1 || maxBytes > 65536
                    || fileName == null || !Set.of("stdout.log", "stderr.log").contains(fileName)) throw new SshException(INVALID_LIMIT);
            Path root = absoluteNormalized(allowedRoot);
            Path directory = absoluteNormalized(privateDirectory);
            if (!validJobDirectory(directory.toString(), directory.getFileName().toString())
                    || !directory.startsWith(root)) throw new SshException(COMMAND_NOT_ALLOWED);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session))
            {
                if (!root.toString().equals(sftp.canonicalPath(root.toString())))
                    throw new SshException(COMMAND_NOT_ALLOWED);
                Path current = root;
                SftpClient.Attributes rootAttributes = sftp.lstat(current.toString());
                if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) throw new SshException(COMMAND_NOT_ALLOWED);
                for (Path segment : root.relativize(directory))
                {
                    current = current.resolve(segment);
                    SftpClient.Attributes attributes = sftp.lstat(current.toString());
                    if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new SshException(COMMAND_NOT_ALLOWED);
                }
                String path = directory.resolve(fileName).toString();
                SftpClient.Attributes file = lstatOrNull(sftp, path);
                if (file == null || offset >= file.getSize()) return new byte[0];
                if (!file.isRegularFile() || file.isSymbolicLink()) throw new SshException(COMMAND_NOT_ALLOWED);
                int length = (int) Math.min(maxBytes, file.getSize() - offset);
                byte[] buffer = new byte[length];
                int read = 0;
                try (SftpClient.CloseableHandle handle = sftp.open(path, SftpClient.OpenMode.Read))
                {
                    while (read < length)
                    {
                        int amount = sftp.read(handle, offset + read, buffer, read, length - read);
                        if (amount <= 0) break;
                        read += amount;
                    }
                }
                return read == length ? buffer : java.util.Arrays.copyOf(buffer, read);
            }
            catch (SshException e) { throw e; }
            catch (IOException e) { throw new SshException(COMMAND_FAILED); }
        }

        private static SftpClient.Attributes lstatOrNull(SftpClient client, String path) throws IOException
        {
            try { return client.lstat(path); }
            catch (SftpException failure)
            {
                if (failure.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return null;
                throw failure;
            }
        }

        @Override public synchronized void close() { closed = true; session.close(true); }
    }

    private static boolean allowedCommand(List<String> command)
    {
        if (COMMANDS.contains(command)) return true;
        if (command.size() == 4 && command.get(0).equals("loginctl") && command.get(1).equals("show-user")
                && command.get(2).matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}[$]?")
                && command.get(3).equals("--property=Linger")) return true;
        if (command.size() == 6 && command.get(0).equals("systemctl") && command.get(1).equals("--user")
                && command.get(2).equals("show") && validUnit(command.get(3))
                && command.get(4).equals("--no-pager") && command.get(5).equals("--property=" + SYSTEMD_PROPERTIES)) return true;
        if (command.size() == 4 && command.get(0).equals("systemctl") && command.get(1).equals("--user")
                && command.get(2).equals("stop") && validUnit(command.get(3))) return true;
        if (command.size() == 10 && command.get(0).equals("systemd-run") && command.get(1).equals("--user")
                && command.get(2).equals("--no-block") && command.get(3).startsWith("--unit=")
                && validUnit(command.get(3).substring("--unit=".length()))
                && command.get(4).equals("--property=KillMode=control-group")
                && command.get(8).equals("/usr/bin/bash"))
        {
            String unit = command.get(3).substring("--unit=".length());
            String uuid = unit.substring("gputrain-".length(), unit.length() - ".service".length());
            String work = command.get(5).startsWith("--property=WorkingDirectory=")
                    ? command.get(5).substring("--property=WorkingDirectory=".length()) : "";
            return validJobDirectory(work, uuid)
                    && command.get(6).equals("--property=StandardOutput=append:" + work + "/stdout.log")
                    && command.get(7).equals("--property=StandardError=append:" + work + "/stderr.log")
                    && command.get(9).equals(work + "/run.sh");
        }
        return false;
    }

    /** 提案渲染期白名单预校验公开入口；执行期 VerifiedSession.exec 内仍逐条复检（双保险）。 */
    public static boolean isAllowedCommand(List<String> command)
    {
        return allowedCommand(command);
    }

    private static boolean validUnit(String value)
    { return value != null && value.matches("gputrain-[0-9a-fA-F-]{36}\\.service"); }

    private static boolean validJobDirectory(String value, String uuid)
    {
        if (value == null || !value.startsWith("/") || value.chars().anyMatch(ch -> ch < 32 || ch == 127)) return false;
        Path path = Path.of(value);
        return path.normalize().equals(path) && path.getFileName().toString().equals(uuid)
                && path.getParent() != null && path.getParent().getFileName().toString().equals("jobs")
                && ".gputrain".equals(path.getParent().getParent().getFileName().toString());
    }

    private static Path absoluteNormalized(String value)
    {
        if (value == null || value.isBlank()) throw new SshException(COMMAND_NOT_ALLOWED);
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new SshException(COMMAND_NOT_ALLOWED);
        return path;
    }

    private static String shellQuote(String value)
    { return "'" + value.replace("'", "'\\''") + "'"; }

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
