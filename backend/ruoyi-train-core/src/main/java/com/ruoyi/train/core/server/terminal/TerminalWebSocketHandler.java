package com.ruoyi.train.core.server.terminal;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.ruoyi.train.core.server.terminal.TerminalMessageCodec.ClientMessage;
import com.ruoyi.train.core.server.terminal.TerminalMessageCodec.Heartbeat;
import com.ruoyi.train.core.server.terminal.TerminalMessageCodec.Input;
import com.ruoyi.train.core.server.terminal.TerminalMessageCodec.ProtocolException;
import com.ruoyi.train.core.server.terminal.TerminalMessageCodec.Resize;
import com.ruoyi.train.core.server.terminal.TerminalTicketService.Grant;

/**
 * 终端 WebSocket 处理器。
 *
 * 时序：拦截器消费票据并把 grant/cols/rows 放入 attributes → 建立连接时
 * 同步完成 SSH 建连（最长约 15s，期间到达的信令丢弃）→ started 信令后进入
 * 正常的 input/resize/heartbeat 路由。任何一侧断开都会触发幂等级联回收。
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler
{
    static final String GRANT_KEY = "terminal.grant";
    static final String COLS_KEY = "terminal.cols";
    static final String ROWS_KEY = "terminal.rows";
    static final String SESSION_KEY = "terminal.session";

    private final TerminalConnectService connectService;

    public TerminalWebSocketHandler(TerminalConnectService connectService)
    {
        this.connectService = connectService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception
    {
        Grant grant = (Grant) session.getAttributes().get(GRANT_KEY);
        int cols = (int) session.getAttributes().getOrDefault(COLS_KEY, 80);
        int rows = (int) session.getAttributes().getOrDefault(ROWS_KEY, 24);
        try
        {
            TerminalSession terminal = connectService.open(grant, session, cols, rows);
            session.getAttributes().put(SESSION_KEY, terminal);
            terminal.sendStarted();
        }
        catch (TerminalException e)
        {
            fail(session, e.code());
        }
        catch (Exception e)
        {
            fail(session, "INTERNAL");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception
    {
        TerminalSession terminal = terminal(session);
        if (terminal == null) return; // 建连进行中或已关闭：丢弃
        try
        {
            ClientMessage decoded = TerminalMessageCodec.decodeClient(message.getPayload());
            if (decoded instanceof Input input) terminal.handleInput(input.data());
            else if (decoded instanceof Resize resize) terminal.resize(resize.cols(), resize.rows());
            else if (decoded instanceof Heartbeat) terminal.heartbeat();
        }
        catch (ProtocolException e)
        {
            terminal.close("PROTOCOL");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception)
    {
        TerminalSession terminal = terminal(session);
        if (terminal != null) terminal.close("SSH_CLOSED");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
    {
        TerminalSession terminal = terminal(session);
        if (terminal != null) terminal.close("SSH_CLOSED");
    }

    private TerminalSession terminal(WebSocketSession session)
    {
        return (TerminalSession) session.getAttributes().get(SESSION_KEY);
    }

    private void fail(WebSocketSession session, String code) throws Exception
    {
        try { session.sendMessage(new TextMessage(TerminalMessageCodec.error(code, TerminalException.describe(code)))); }
        catch (Exception ignored) { }
        session.close(CloseStatus.NORMAL);
    }
}
