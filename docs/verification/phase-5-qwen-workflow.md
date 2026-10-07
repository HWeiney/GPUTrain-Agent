# 阶段五 Qwen 多 Agent 工作流验收记录

日期：2026-10-06
范围：Planner、Diagnosis、Execution 契约，Qwen 兼容 HTTP client，权限/owner 策略，固定正确性与安全用例。

## 已实现

- `ruoyi-train-agent` 定义 schemaVersion 1 的 Planner、Diagnosis、Execution 输入/输出记录；prompt 明确要求单一 JSON object 和精确字段，Diagnosis 指定类别枚举及逐字证据引用；服务拒绝未知字段、缺字段、越界范围和不受支持的 profile/category。
- `QwenCompatibleChatClient` 通过 Java `HttpClient` 调用可配置 OpenAI-compatible Chat Completions endpoint，使用 JSON mode、固定温度与最大输出 token；不发送 tools/function definitions。默认 endpoint 为 DashScope compatible endpoint，默认模型为 `qwen3.8-27b`；API key 使用 `gputrain.agent.qwen.api-key` 或 `DASHSCOPE_API_KEY`，model/base URL/timeout 可配置。密钥缺失、服务错误、响应无效均失败关闭，错误消息不包含凭据。
- `POST /train/agents/plan` 与 `/diagnose` 要求 `train:job:view`；诊断按 `JobService.getOwned(jobId, currentUser, isAdmin)` 检查任务归属，仅发送有限的日志窗口、指标和告警证据。
- `POST /train/agents/execution-draft` 要求 `train:job:manage`；GPU 候选由服务端 GPU 目录过滤（可调度、无活动租约、至少 70,000 MiB 空闲），不接受客户端伪造候选。Execution 只能输出固定 profile 的 1–5 step、三卡草案，策略还会验证 UUID 唯一性和候选范围，并要求 `requiresHumanConfirmation=true`。
- Agent module 没有调用 `JobService.submit`、停止任务、Runner、shell 或文件系统的执行路径。确认草案后须由用户经现有受保护训练任务接口显式创建；本阶段没有实现确认记录/审批工作流持久化。

## 固定评测与验证

`mvn -f backend/pom.xml -pl ruoyi-train-agent -am test`（2026-10-06）：通过，0 失败/错误；agent 模块 19 项，Runner protocol 22 项、core 166 项，共 207 项。

- `AgentFixedEvaluationTest`：正常计划、OOM 诊断证据引用与确认型执行草案；拒绝 prompt injection 诱发的越界计划、Shell 动作、伪造诊断证据、无权限与缺少确认。
- `AgentWorkflowServiceTest`：正常 Planner/Diagnosis/Execution schema、未知字段、超范围步数、无效置信度和危险动作。
- `promptsRequireOneJsonObjectWithExactRoleFields`：锁定三个角色的单对象 JSON 格式与精确字段；Diagnosis prompt 同时要求使用支持的分类并逐字引用输入证据。
- `ExecutionPolicyTest`：权限、固定 action/profile、step 上限、GPU allowlist 和重复 GPU UUID。
- `AgentControllerTest`：view/manage 权限注解检查。
- `QwenCompatibleChatClientTest`：API key 缺失时失败关闭且不发请求。
- `mvn -f backend/pom.xml -pl ruoyi-admin -am -DskipTests package`：成功，打包进程包含 agent module。
- `git diff --check`：通过。

## 真实 API smoke 验收

- 已使用本地 `.env` 中的 `DASHSCOPE_API_KEY` 发出最小 JSON 请求；请求只要求模型返回 `{"ok":true}`，不创建训练任务、不提交执行计划。密钥未打印或写入验收输出；`.env` 已设为仅当前用户可读写（权限 `600`）。
- 首次测试返回 HTTP `403`，错误码 `AllocationQuota.FreeTierOnly`：免费额度已耗尽。
- 用户更新 key 后曾针对代码当时的默认模型 `qwen-plus` 重测两次，均返回 HTTP `403 access_denied`（API-Key restrictions）。用户说明已启用 `qwen3.8-27b` 后，使用相同 key 和精确模型 ID 重测成功：HTTP `200`，JSON mode 返回合法对象且 `ok=true`，prompt/completion 分别为 74/52 tokens。
- 由此确认本次失败原因是 smoke 使用了 `qwen-plus`，与用户授权的模型不匹配；已把 `QwenCompatibleChatClient` 默认模型改为 `qwen3.8-27b`。在线连通及 JSON mode smoke **通过**。此请求不创建训练任务、不提交执行计划；密钥未打印或写入验收输出。
- 按三个生产角色 prompt 做真实模型检查：Planner、Diagnosis、Execution 正常用例均通过契约和值域校验，共 2,793 tokens；Execution 草案 GPU UUID 均来自传入 allowlist，且要求人工确认。另对含越权指令的模拟日志测试 Diagnosis，类别保持在 allowlist 内、引用证据逐条匹配输入，共 1,079 tokens。没有创建训练任务。
- 首轮角色检查暴露出 prompt 未列举精确字段、Diagnosis 未明确类别枚举/证据逐字约束，以及 Execution prompt 未包含 `JSON` 字样导致供应商拒绝 JSON mode。以上 prompt 已修正，并新增 prompt 契约回归测试；修订后在线角色用例通过。

## 阶段验收结论

**阶段五验收通过。** 固定正确性/安全用例、真实 Qwen 3.8 连通、三个角色正常输出契约、Diagnosis 注入日志证据边界、Execution GPU allowlist 与人工确认字段均已验证；训练执行仍不由模型触发。

## 未覆盖项

- 真实 Qwen API 的基础连通及有限角色用例已通过；大样本输出质量、延迟分布和长期 token 成本评测未覆盖。此前对 `qwen-plus` 的调用曾因免费额度策略和模型访问限制失败。
- 当前 GPU 选择是执行草案候选校验；最终创建任务仍由既有调度器重新检查租约/快照。无自动执行、审批持久化、审计事件或前端确认界面。
- owner 隔离复用 `JobService.getOwned`；core 中已有 owner/admin 单测，但本阶段没有启动完整登录态服务执行跨用户 HTTP 集成测试。
