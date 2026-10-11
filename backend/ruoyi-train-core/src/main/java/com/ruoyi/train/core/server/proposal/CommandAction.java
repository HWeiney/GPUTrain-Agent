package com.ruoyi.train.core.server.proposal;

import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;

/**
 * 命令提案动作模板：动作名 + JSON 参数在后端渲染为完整 argv，永不接受自由 shell 文本。
 * 渲染产物必须通过 {@link com.ruoyi.train.core.server.ssh.MinaSshGateway#isAllowedCommand}
 * 白名单校验（创建期预检 + 批准期复检，执行期网关内仍逐条复检）。
 */
public interface CommandAction
{
    String name();

    String description();

    List<ActionParam> params();

    /** 按参数渲染完整命令 argv；非法参数抛 ServiceException("INVALID_PROPOSAL_REQUEST")。 */
    List<String> render(JsonNode args, CommandContext ctx);

    /** 动作参数元数据：前端动态表单与后端校验的单一事实源。 */
    record ActionParam(String name, String type, boolean required, String description)
    {
    }

    /** 渲染上下文：提案归属者 + 目标服务器/账号（脱敏视图，不含凭证密文）。 */
    record CommandContext(Long actorUserId, ServerRecord server, ServerAccountRecord account)
    {
    }

    /** 零参只读探测动作：渲染固定 argv，拒绝任何参数。 */
    static CommandAction fixed(String name, String description, List<String> command)
    {
        Objects.requireNonNull(command);
        return new CommandAction()
        {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public List<ActionParam> params() { return List.of(); }
            @Override public List<String> render(JsonNode args, CommandContext ctx)
            {
                if (args != null && args.size() > 0) throw new ServiceException("INVALID_PROPOSAL_REQUEST");
                return command;
            }
        };
    }
}
