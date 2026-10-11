package com.ruoyi.train.core.server.terminal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 终端 WebSocket 端点注册。
 *
 * 认证说明：浏览器 WS 握手无法携带 Authorization 头，因此该路径在
 * SecurityConfig 中 permitAll，真正的鉴权由 {@link TerminalHandshakeInterceptor}
 * 消费一次性票据完成（票据签发端点已强制 train:terminal:connect 权限）。
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(TerminalProperties.class)
public class TerminalWebSocketConfig implements WebSocketConfigurer
{
    private final TerminalWebSocketHandler handler;
    private final TerminalHandshakeInterceptor interceptor;

    public TerminalWebSocketConfig(TerminalWebSocketHandler handler, TerminalHandshakeInterceptor interceptor)
    {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(handler, "/websocket/terminal")
            .addInterceptors(interceptor)
            // 鉴权完全由一次性票据保证（不依赖 cookie/Origin），生产经 Nginx 反代时
            // Origin(站点域名) 与转发后 Host(后端地址) 不同，默认 SameOrigin 会拒绝握手
            .setAllowedOriginPatterns("*");
    }
}
