package com.ruoyi.train.core.server.proposal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;

/**
 * 动作注册表：按动作名查找模板。动作集由 {@link CommandActionConfig} 统一装配
 * （注册顺序即目录顺序），新增动作只改装配类，注册表与前端目录自动跟进。
 */
@Component
public class CommandActionRegistry
{
    private final Map<String, CommandAction> actions;

    public CommandActionRegistry(List<CommandAction> actions)
    {
        Map<String, CommandAction> indexed = new LinkedHashMap<>();
        for (CommandAction action : actions)
        {
            if (indexed.put(action.name(), action) != null)
                throw new IllegalStateException("Duplicate proposal action: " + action.name());
        }
        this.actions = Collections.unmodifiableMap(indexed);
    }

    public CommandAction action(String name)
    {
        CommandAction action = name == null ? null : actions.get(name);
        if (action == null) throw new ServiceException("UNKNOWN_PROPOSAL_ACTION");
        return action;
    }

    public List<CommandAction> catalog()
    {
        return List.copyOf(actions.values());
    }
}
