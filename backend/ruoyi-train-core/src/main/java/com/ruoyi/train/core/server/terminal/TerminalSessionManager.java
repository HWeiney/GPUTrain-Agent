package com.ruoyi.train.core.server.terminal;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 终端会话治理：per-user / total 双闸门、活跃会话注册表、空闲与时长巡检。
 * 与 exec 网关（{@code SshGateway}）的并发槽完全独立，互不挤占。
 */
@Component
public class TerminalSessionManager
{
    private static final long SWEEP_INTERVAL_SECONDS = 30;

    private final TerminalProperties properties;
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ExecutorService io = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "terminal-io");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "terminal-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    public TerminalSessionManager(TerminalProperties properties)
    {
        this.properties = properties;
    }

    /** 预占一个会话配额（连接建立前调用），超限抛出 SESSION_LIMIT。 */
    public void reserve(Long userId)
    {
        if (total.incrementAndGet() > properties.maxTotalSessions())
        {
            total.decrementAndGet();
            throw new TerminalException("SESSION_LIMIT");
        }
        AtomicInteger userCount = perUser.computeIfAbsent(userId, key -> new AtomicInteger());
        if (userCount.incrementAndGet() > properties.maxSessionsPerUser())
        {
            userCount.decrementAndGet();
            total.decrementAndGet();
            throw new TerminalException("SESSION_LIMIT");
        }
    }

    /** 归还预占配额（仅在连接建立失败时调用；成功路径由 {@link #remove} 负责）。 */
    public void release(Long userId)
    {
        total.decrementAndGet();
        AtomicInteger userCount = perUser.get(userId);
        if (userCount != null) userCount.decrementAndGet();
    }

    /** 连接建立成功后注册；此后空闲/时长巡检和关闭回收都由管理器统一调度。 */
    public void register(TerminalSession session)
    {
        sessions.put(session.sessionUuid(), session);
    }

    /** 会话关闭时移除并归还配额（幂等：仅首次移除成功时归还）。 */
    public void remove(TerminalSession session)
    {
        if (sessions.remove(session.sessionUuid(), session)) release(session.userId());
    }

    /** 投递键盘输入/resize 到共享 IO 线程，避免远端窗口满时阻塞 WebSocket 容器线程。 */
    public void submitIo(TerminalSession session, Runnable task)
    {
        io.execute(task);
    }

    public Collection<TerminalSession> sessions()
    {
        return sessions.values();
    }

    @PostConstruct void start()
    {
        sweeper.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy void shutdown()
    {
        sweeper.shutdownNow();
        io.shutdownNow();
        sessions.values().forEach(session -> session.close("INTERNAL"));
        sessions.clear();
    }

    /** 巡检：空闲超时 / 达到时长上限的会话主动关闭。包可见以便集成测试直接触发。 */
    void sweep()
    {
        for (TerminalSession session : sessions.values())
        {
            if (session.durationExpired(properties.maxSessionDuration())) session.close("MAX_DURATION");
            else if (session.idleExpired(properties.idleTimeout())) session.close("IDLE_TIMEOUT");
        }
    }
}
