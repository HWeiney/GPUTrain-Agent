package com.ruoyi.train.core.server.terminal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web 终端会话治理参数（gputrain.terminal.*）。
 * record 走构造器绑定，由 {@link TerminalWebSocketConfig} 上的
 * {@code @EnableConfigurationProperties} 注册（不能用 @Component，否则按普通构造注入解析）。
 *
 * @param idleTimeout         输入输出均无活动的会话将被回收
 * @param maxSessionDuration  单个终端会话的绝对时长上限
 * @param maxSessionsPerUser  每个用户允许并存的终端会话数
 * @param maxTotalSessions    全局允许并存的终端会话总数（独立于 exec 网关的并发槽）
 * @param ticketTtl           一次性握手票据的有效期
 */
@ConfigurationProperties(prefix = "gputrain.terminal")
public record TerminalProperties(
    @DefaultValue("30m") Duration idleTimeout,
    @DefaultValue("12h") Duration maxSessionDuration,
    @DefaultValue("3") int maxSessionsPerUser,
    @DefaultValue("16") int maxTotalSessions,
    @DefaultValue("60s") Duration ticketTtl)
{
    public TerminalProperties
    {
        requirePositive(idleTimeout, "idle-timeout");
        requirePositive(maxSessionDuration, "max-session-duration");
        requirePositive(ticketTtl, "ticket-ttl");
        if (maxSessionsPerUser < 1 || maxTotalSessions < 1 || maxSessionsPerUser > maxTotalSessions)
            throw new IllegalArgumentException("gputrain.terminal session limits must satisfy 1 <= max-sessions-per-user <= max-total-sessions");
    }

    private static void requirePositive(Duration value, String name)
    {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException("gputrain.terminal." + name + " must be a positive duration");
    }
}
