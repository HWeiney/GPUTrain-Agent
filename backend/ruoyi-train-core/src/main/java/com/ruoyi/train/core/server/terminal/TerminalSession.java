package com.ruoyi.train.core.server.terminal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import com.ruoyi.train.core.server.terminal.mapper.TerminalAuditMapper;

/**
 * 单个终端会话：SSH shell 通道与 WebSocket 会话之间的双向桥接。
 *
 * 背压设计：远端输出经 {@link OutputSink} 直接送入并发受限的 WebSocket 装饰器
 * （发送超时 5s / 缓冲上限 256KB，超限抛出异常并触发关闭），不占用独立泵线程；
 * 键盘输入与 resize 投递到共享 IO 线程池串行写入，避免远端窗口满时阻塞容器线程。
 * 关闭幂等：任一侧断开都会级联回收全部资源并归还配额。
 */
final class TerminalSession implements AutoCloseable
{
    /** 单个 BINARY 帧承载的最大 shell 输出字节数。 */
    static final int MAX_FRAME_BYTES = 16 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT = 256 * 1024;

    private final String sessionUuid;
    private final Long userId;
    private final Long serverId;
    private final Long accountId;
    private final TerminalSessionManager manager;
    private final TerminalAuditMapper audit;
    private final SshClient sshClient;
    private final ClientSession clientSession;
    private final ChannelShell channel;
    private final WebSocketSession ws;
    private final Instant startedAt = Instant.now();
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final AtomicBoolean closed = new AtomicBoolean();

    TerminalSession(String sessionUuid, Long userId, Long serverId, Long accountId, TerminalSessionManager manager,
        TerminalAuditMapper audit, SshClient sshClient, ClientSession clientSession, ChannelShell channel,
        WebSocketSession ws)
    {
        this.sessionUuid = sessionUuid;
        this.userId = userId;
        this.serverId = serverId;
        this.accountId = accountId;
        this.manager = manager;
        this.audit = audit;
        this.sshClient = sshClient;
        this.clientSession = clientSession;
        this.channel = channel;
        // 背压防线：客户端长期不读时缓冲受限并快速失败，而不是无限堆积
        this.ws = new ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        channel.setOut(new OutputSink());
        channel.setErr(new OutputSink());
    }

    String sessionUuid() { return sessionUuid; }

    /** 会话是否仍然存活（尚未进入关闭流程）。 */
    boolean isOpen() { return !closed.get(); }

    Long userId() { return userId; }

    Long serverId() { return serverId; }

    Long accountId() { return accountId; }

    Instant startedAt() { return startedAt; }

    boolean idleExpired(Duration idleTimeout)
    {
        return idleTimeout.toNanos() <= System.nanoTime() - lastActivity.get();
    }

    boolean durationExpired(Duration maxSessionDuration)
    {
        return maxSessionDuration.compareTo(Duration.between(startedAt, Instant.now())) <= 0;
    }

    /** 连接建立成功后通知前端（此后 shell 输出开始流动）。 */
    void sendStarted()
    {
        touch();
        try { ws.sendMessage(new TextMessage(TerminalMessageCodec.started(sessionUuid))); }
        catch (IOException e) { close("BACKPRESSURE"); }
    }

    /** 键盘输入：投递到共享 IO 线程串行写入，远端窗口满时不阻塞容器线程。 */
    void handleInput(byte[] data)
    {
        touch();
        manager.submitIo(this, () -> {
            try
            {
                var input = channel.getInvertedIn();
                input.write(data);
                input.flush();
            }
            catch (IOException e) { close("SSH_CLOSED"); }
        });
    }

    /** 终端尺寸变化。 */
    void resize(int cols, int rows)
    {
        touch();
        manager.submitIo(this, () -> {
            try { channel.sendWindowChange(cols, rows); }
            catch (IOException e) { close("SSH_CLOSED"); }
        });
    }

    /** 客户端心跳：仅刷新活跃时间。 */
    void heartbeat() { touch(); }

    void touch() { lastActivity.set(System.nanoTime()); }

    /** 幂等关闭：回收 SSH/WebSocket 资源并归还会话配额。 */
    void close(String reason)
    {
        if (!closed.compareAndSet(false, true)) return;
        manager.remove(this);
        try { audit.finishAttempt(sessionUuid, reason, Instant.now()); } catch (Exception ignored) { }
        try { ws.sendMessage(new TextMessage(TerminalMessageCodec.closed(reason))); } catch (Exception ignored) { }
        try { channel.close(true); } catch (Exception ignored) { }
        try { clientSession.close(true); } catch (Exception ignored) { }
        try { sshClient.close(true); } catch (Exception ignored) { }
        try { ws.close(CloseStatus.NORMAL); } catch (Exception ignored) { }
    }

    @Override public void close() { close("SSH_CLOSED"); }

    /** shell 输出直通 sink：MINA IO 线程调用，按 16KB 分帧发送 BINARY 帧。 */
    private final class OutputSink extends OutputStream
    {
        @Override public void write(int value)
        {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override public void write(byte[] value, int offset, int length)
        {
            if (closed.get() || length <= 0) return;
            touch();
            int position = offset;
            int end = offset + length;
            while (position < end)
            {
                int size = Math.min(MAX_FRAME_BYTES, end - position);
                // 必须复制：MINA 会复用读取缓冲数组，直接 wrap 会导致已发送帧的 payload 被后续数据覆盖
                byte[] frame = Arrays.copyOfRange(value, position, position + size);
                try { ws.sendMessage(new BinaryMessage(ByteBuffer.wrap(frame))); }
                catch (IOException e)
                {
                    TerminalSession.this.close("BACKPRESSURE");
                    return;
                }
                position += size;
            }
        }
    }
}
