package com.ruoyi.train.core.server.terminal;

import java.util.Base64;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Web 终端 WebSocket 控制信令编解码。
 *
 * 协议约定：
 *  - 客户端 → 服务端：TEXT 帧 JSON，type = input / resize / heartbeat
 *  - 服务端 → 客户端：控制信令为 TEXT 帧 JSON，type = started / error / closed / heartbeat；
 *    shell 原始输出为 BINARY 帧（不经本类编码）
 *  - 键盘输入以 base64 承载，避免 JSON 转义与控制字符问题
 */
public final class TerminalMessageCodec
{
    /** 单条 input 信令解码后的最大字节数。 */
    static final int MAX_INPUT_BYTES = 16 * 1024;
    static final int MIN_DIMENSION = 1;
    static final int MAX_COLS = 500;
    static final int MAX_ROWS = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    public sealed interface ClientMessage permits Input, Resize, Heartbeat { }

    public record Input(byte[] data) implements ClientMessage { }

    public record Resize(int cols, int rows) implements ClientMessage { }

    public record Heartbeat() implements ClientMessage { }

    /** 信令非法（无法解析、字段越界等），调用方应关闭连接。 */
    public static final class ProtocolException extends Exception { }

    private TerminalMessageCodec() { }

    public static ClientMessage decodeClient(String text) throws ProtocolException
    {
        try
        {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) throw new ProtocolException();
            String type = root.path("type").asText("");
            switch (type)
            {
                case "input":
                {
                    byte[] data = Base64.getDecoder().decode(root.path("data").asText(""));
                    if (data.length == 0 || data.length > MAX_INPUT_BYTES) throw new ProtocolException();
                    return new Input(data);
                }
                case "resize":
                {
                    int cols = root.path("cols").asInt(0);
                    int rows = root.path("rows").asInt(0);
                    if (cols < MIN_DIMENSION || cols > MAX_COLS || rows < MIN_DIMENSION || rows > MAX_ROWS)
                        throw new ProtocolException();
                    return new Resize(cols, rows);
                }
                case "heartbeat":
                    return new Heartbeat();
                default:
                    throw new ProtocolException();
            }
        }
        catch (ProtocolException ex) { throw ex; }
        catch (JsonProcessingException | IllegalArgumentException ex)
        {
            throw new ProtocolException();
        }
    }

    public static String started(String sessionUuid)
    {
        return control("started").put("sessionUuid", sessionUuid).toString();
    }

    public static String error(String code, String message)
    {
        ObjectNode node = control("error").put("code", code);
        if (message != null && !message.isBlank()) node.put("message", message);
        return node.toString();
    }

    public static String closed(String reason)
    {
        return control("closed").put("reason", reason).toString();
    }

    public static String heartbeat()
    {
        return control("heartbeat").toString();
    }

    private static ObjectNode control(String type)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        return node;
    }
}
