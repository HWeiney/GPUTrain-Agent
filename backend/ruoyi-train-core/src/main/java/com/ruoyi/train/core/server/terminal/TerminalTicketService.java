package com.ruoyi.train.core.server.terminal;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.redis.RedisCache;

/**
 * 终端握手票据：REST 端点签发（签发前已通过 @PreAuthorize 与账号归属校验），
 * WebSocket 握手时一次性消费。
 *
 * 浏览器的 WebSocket 握手无法携带 Authorization 头，因此用「先取票据再握手」的两段式：
 * 票据即权限证明，60 秒内有效且只能使用一次，权限回收窗口可忽略。
 */
@Service
public class TerminalTicketService
{
    private static final String KEY_PREFIX = "terminal:ticket:";
    private static final int TICKET_BYTES = 32;

    private final RedisCache redisCache;
    private final TerminalProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TerminalTicketService(RedisCache redisCache, TerminalProperties properties)
    {
        this.redisCache = redisCache;
        this.properties = properties;
    }

    /** 票据携带的授权：哪个用户连哪台服务器上的哪个账号。 */
    public record Grant(Long userId, Long serverId, Long accountId)
    {
        String encode() { return userId + ":" + serverId + ":" + accountId; }

        static Grant decode(String payload)
        {
            String[] fields = payload.split(":", -1);
            if (fields.length != 3) return null;
            try { return new Grant(Long.parseLong(fields[0]), Long.parseLong(fields[1]), Long.parseLong(fields[2])); }
            catch (NumberFormatException ignored) { return null; }
        }
    }

    /** 签发票据并返回票据字符串。 */
    public String issueTicket(Long userId, Long serverId, Long accountId)
    {
        Grant grant = new Grant(userId, serverId, accountId);
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = HexFormat.of().formatHex(bytes);
        redisCache.setCacheObject(KEY_PREFIX + ticket, grant.encode(),
            (int) properties.ticketTtl().toSeconds(), TimeUnit.SECONDS);
        return ticket;
    }

    /**
     * 一次性消费票据：并发握手时以 Redis delete 结果裁决，仅一方成功。
     *
     * @return 授权信息；票据不存在、格式非法或已被使用时返回 null
     */
    public Grant consume(String ticket)
    {
        if (ticket == null || ticket.length() != TICKET_BYTES * 2 || !ticket.matches("[0-9a-f]+")) return null;
        String payload = redisCache.getCacheObject(KEY_PREFIX + ticket);
        if (payload == null) return null;
        if (!redisCache.deleteObject(KEY_PREFIX + ticket)) return null;
        return Grant.decode(payload);
    }

    public long ticketTtlMillis() { return properties.ticketTtl().toMillis(); }
}
