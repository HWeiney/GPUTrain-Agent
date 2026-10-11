package com.ruoyi.train.core.server.terminal;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import com.ruoyi.train.core.server.terminal.TerminalTicketService.Grant;

/**
 * 终端握手拦截器：一次性消费 REST 签发的票据（票据即权限证明，
 * 签发端点已通过 @PreAuthorize 强制 train:terminal:connect 与账号归属校验）。
 */
@Component
public class TerminalHandshakeInterceptor implements HandshakeInterceptor
{
    private final TerminalTicketService tickets;

    public TerminalHandshakeInterceptor(TerminalTicketService tickets)
    {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
        WebSocketHandler wsHandler, Map<String, Object> attributes)
    {
        Map<String, String> params = UriComponentsBuilder.fromUri(request.getURI())
            .build().getQueryParams().toSingleValueMap();
        Grant grant = tickets.consume(params.get("ticket"));
        if (grant == null)
        {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(TerminalWebSocketHandler.GRANT_KEY, grant);
        attributes.put(TerminalWebSocketHandler.COLS_KEY, parseDimension(params.get("cols"), 80));
        attributes.put(TerminalWebSocketHandler.ROWS_KEY, parseDimension(params.get("rows"), 24));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
        WebSocketHandler wsHandler, Exception exception)
    {
    }

    /** 尺寸参数按 codec 约束收敛，非法值回落默认。 */
    private static int parseDimension(String value, int fallback)
    {
        if (value == null) return fallback;
        try
        {
            int dimension = Integer.parseInt(value);
            if (dimension < TerminalMessageCodec.MIN_DIMENSION
                || dimension > Math.max(TerminalMessageCodec.MAX_COLS, TerminalMessageCodec.MAX_ROWS))
                return fallback;
            return dimension;
        }
        catch (NumberFormatException ignored)
        {
            return fallback;
        }
    }
}
