# 阶段五：Qwen 多 Agent 工作流设计

## 目标与范围

在现有 Spring Boot 控制面中实现 Planner、Diagnosis、Execution 三个职责明确的 Agent。Agent 输入输出使用版本化 JSON 契约；Qwen 只生成结构化建议，不获得业务工具或 Runner 凭证。阶段五提供计划、诊断和执行草案 API，不由模型直接启动、停止或恢复任务。

本阶段不实现 MCP、IDE 客户端、Agent 对话记忆、任意工具调用、自动 GPU 选择、审批工作流持久化或训练任务自动执行。

## 方案选择

1. 使用 Spring AI 与模型 SDK：框架能力完整，但增加依赖、版本和供应商适配面。
2. 使用 Java `HttpClient` 对接可配置的 Qwen OpenAI 兼容 Chat Completions API（推荐）：依赖少，模型名、endpoint、API key 可由配置提供；测试使用固定模型替身。
3. 只定义接口、暂不接模型：风险最低，但无法覆盖 Qwen 请求/响应适配。

选择方案 2。模型只返回 JSON，不启用 function/tool calling；API key 缺失或模型响应格式错误时请求失败关闭，不回退到可执行操作。

## 契约

- `PlannerInput`: `schemaVersion`, `requestText`。
- `PlannerOutput`: `schemaVersion`, `objective`, `profileId`, `maxSteps`, `gpuCount`, `constraints`, `assumptions`, `risks`。profile 仅允许平台已支持的 `docowl2-srfund`，范围字段由服务端校验。
- `DiagnosisInput`: `schemaVersion`, `jobId`, `status`, `exitCode`, `logEvidence`, `metrics`, `alerts`。调用方必须先按当前用户验证 job 所有权；证据条数和文本长度有限制，发送给模型前仅传短窗口。
- `DiagnosisOutput`: `schemaVersion`, `category`, `confidence`, `evidence`, `probableCauses`, `recommendations`。建议是文本，不携带工具调用。
- `ExecutionInput`: `schemaVersion`, `plan`, `availableGpuUuids`。
- `ExecutionPlan`: `schemaVersion`, 固定 `action=CREATE_TRAINING_JOB`、profile、step 数、GPU UUID 列表、constraints、`requiresHumanConfirmation=true`。契约没有 shell 字段、可执行路径字段或原始环境变量字段。

响应严格解析为 JSON 对象，拒绝未知字段、版本不符、字段越界和不支持操作。拒绝结果返回稳定 policy code，不将原始响应或密钥写入日志。

## 权限与人工确认

- Planner 和 Diagnosis API 需要 `train:job:view`；诊断服务对 job ID 再执行 owner/admin 校验。
- Execution 草案 API 需要 `train:job:manage`，并由服务端基于白名单数据重新验证输出；用户输入的权限声明不作为授权依据。
- 创建训练任务属于 `CONFIRM_REQUIRED`：Execution 永远只返回草案和确认要求；本阶段没有 Agent 到 `JobService`、Runner、shell 或文件系统的调用边。用户需在现有受权限保护的人工操作入口执行任务。无确认、权限不足、profile/参数/GPU 白名单不符均拒绝。
- 任意 Shell、命令字符串、文件删除、Runner 归属变更均为 `FORBIDDEN`，不可由模型输出覆盖。

## 组件与数据流

`AgentController -> AgentWorkflowService -> StructuredAgentModel -> QwenCompatibleChatClient`。Workflow Service 负责版本化提示、严格解析和数据最小化；`ExecutionPolicy` 是模型输出后的确定性安全门。Qwen client 使用配置的 base URL、model、API key 与 timeout。测试注入 `StructuredAgentModel` 固定响应，不访问外部网络。

## 固定验收

- 正确性：正常训练请求产生符合 schema 的 Planner 计划；失败日志证据被归纳为带置信度、引用证据的 Diagnosis；安全执行草案只含固定 profile 和合法 GPU UUID，且标注人工确认。
- 安全性：越界 step、错误 schema、未知 JSON 字段、shell/删除/权限提升动作、无 `train:job:manage` 身份、非白名单 profile、无效 GPU UUID 均拒绝；模型永远没有执行工具。
- Qwen 适配：API key 缺失、非 2xx、超时、非法 JSON、缺失 choices/content 时失败关闭；错误不泄露 key。

## 验收边界

固定用例验证契约、策略及协议适配；默认不调用真实 Qwen API，不提交训练任务。上线前需通过环境变量提供 API key 并由操作者触发非执行型 smoke request。
