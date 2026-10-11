package com.ruoyi.train.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;

/** 提案起草输出：模型选择的动作 + 参数 + 依据；经严格校验后才用于创建 AGENT 提案。 */
public record ProposalDraftOutput(int schemaVersion, String action, JsonNode args, String rationale)
{
}
