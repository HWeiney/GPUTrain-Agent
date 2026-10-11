package com.ruoyi.train.agent.contract;

/** 目录动作的参数元数据（与通道 B 前端动态表单同源，不含任何秘密）。 */
public record CatalogParam(String name, String type, boolean required, String description)
{
}
