package com.ruoyi.train.core.server.terminal;

/**
 * 终端会话错误。只携带白名单错误码，不携带底层异常细节，
 * 与 {@code SshGateway.SshException} 的脱敏惯例一致。
 */
public final class TerminalException extends RuntimeException
{
    private final String code;

    public TerminalException(String code)
    {
        super(code);
        this.code = code;
    }

    public TerminalException(String code, Throwable cause)
    {
        super(code, cause);
        this.code = code;
    }

    public String code() { return code; }

    /** 面向用户的中文描述白名单；未知码一律按内部错误处理，不透出细节。 */
    public static String describe(String code)
    {
        if (code == null) return "内部错误";
        return switch (code)
        {
            case "TICKET_INVALID" -> "连接票据无效或已过期，请重新发起连接";
            case "ACCOUNT_INVALID" -> "服务器账号不可用或不存在";
            case "SESSION_LIMIT" -> "终端会话数已达上限，请先关闭空闲会话";
            case "CONNECT_TIMEOUT" -> "连接服务器超时";
            case "CONNECT_FAILED" -> "无法连接到服务器";
            case "AUTH_TIMEOUT" -> "认证超时";
            case "AUTH_FAILED" -> "认证失败，请检查账号凭证";
            case "HOST_KEY_CHANGED" -> "服务器主机密钥已变化，连接被拒绝";
            case "MASTER_KEY_NOT_CONFIGURED" -> "系统未配置凭证加密主密钥";
            case "CREDENTIAL_INVALID" -> "凭证解密失败，请更新账号凭证";
            case "HOST_KEY_UNCONFIRMED" -> "服务器主机密钥未确认，请先在服务器管理中确认";
            case "IDLE_TIMEOUT" -> "会话空闲超时，已自动断开";
            case "MAX_DURATION" -> "会话达到最大时长，已自动断开";
            case "BACKPRESSURE" -> "客户端持续未响应，会话已断开";
            case "PROTOCOL" -> "协议信令错误，连接已关闭";
            case "SSH_CLOSED" -> "远程会话已关闭";
            case "INTERNAL" -> "内部错误";
            default -> "内部错误";
        };
    }
}
