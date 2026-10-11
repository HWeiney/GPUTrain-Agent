package com.ruoyi.train.agent.contract;

import java.util.List;

/** 白名单动作目录条目：模型起草提案时可选的动作及其参数声明。 */
public record CatalogAction(String name, String description, List<CatalogParam> params)
{
}
