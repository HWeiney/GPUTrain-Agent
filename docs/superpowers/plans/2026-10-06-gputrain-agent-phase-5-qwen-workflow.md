# 阶段五 Qwen 多 Agent 工作流实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 GPUTrain 控制面提供受策略约束的 Planner、Diagnosis、Execution 草案工作流，并以固定正确性/安全性用例验收。

**Architecture:** `ruoyi-train-agent` 提供契约、Qwen OpenAI 兼容 HTTP adapter、workflow service、策略和受权限保护的 API；模型不能调用任何工具。Planner/Diagnosis 返回建议，Execution 只能返回固定训练 profile 的草案，必须由用户通过现有人工入口创建任务。

**Tech Stack:** Java 17、Spring Boot 3、Jackson、JUnit 5、Java `HttpClient`；Qwen 请求通过环境配置，测试不访问网络。

## Global Constraints

- API key 只从运行时配置读取，不写源码、文档、日志或响应。
- Agent 响应必须为 schemaVersion 1 且无未知 JSON 字段。
- 仅允许 profile `docowl2-srfund` 与 1–5 个 step；GPU UUID 必须由控制面清单校验。
- 模型无业务工具、Shell、Runner 或文件系统访问能力。
- 训练创建、停止和恢复需要 `train:job:manage` 人工操作，Agent 只生成带 `requiresHumanConfirmation=true` 的草案。
- 诊断前按 `JobService.getOwned` 校验用户/管理员数据范围。

---

### Task 1: 增加输入输出契约和严格验证

**Files:**
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/PlannerInput.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/PlannerOutput.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/DiagnosisInput.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/DiagnosisOutput.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/ExecutionInput.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/contract/ExecutionPlan.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/policy/ExecutionPolicy.java`
- Test: `backend/ruoyi-train-agent/src/test/java/com/ruoyi/train/agent/policy/ExecutionPolicyTest.java`

**Interfaces:** `ExecutionPolicy.evaluate(boolean canManage, ExecutionPlan plan, Set<String> availableGpuUuids): PolicyDecision`；固定 profile、step 范围、三卡白名单、确认标记。

- [x] 先写用例：有效草案只能得到 `DRAFT_REQUIRES_CONFIRMATION`；无权限、缺确认、越界 step、任意动作、重复/非白名单 UUID 均拒绝。
- [x] 运行 `mvn -f backend/pom.xml -pl ruoyi-train-agent -am -Dtest=ExecutionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新用例因类/行为缺失失败。
- [x] 添加记录契约和确定性 policy，仅实现上述规则。
- [x] 重跑同一命令，确认用例通过。

### Task 2: 增加结构化 Qwen 适配器与 Agent workflow

**Files:**
- Modify: `backend/ruoyi-train-agent/pom.xml`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/model/StructuredAgentModel.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/model/QwenCompatibleChatClient.java`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/service/AgentWorkflowService.java`
- Create: `backend/ruoyi-train-agent/src/test/java/com/ruoyi/train/agent/service/AgentWorkflowServiceTest.java`

**Interfaces:** `StructuredAgentModel.complete(AgentRole role, String systemPrompt, JsonNode input): JsonNode`；workflow 提供 `plan`、`diagnose`、`draftExecution`。

- [x] 用固定 response fake 写正常 Planner/Diagnosis/Execution 用例，以及未知字段和错误版本/范围拒绝用例。
- [x] 验证 API key 缺失时 Qwen adapter 不发请求、失败消息不回显凭据；外部 HTTP 状态/供应商 JSON 响应未在本地真实调用。
- [x] 运行定向测试观察预期失败。
- [x] 实现配置化 Java HTTP 请求、JSON-only 提示、严格字段解析和 contract 校验，不启用工具调用。
- [x] 重跑定向测试，确认通过。

### Task 3: 接入登录权限 API 和 owner-scoped diagnosis

**Files:**
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/web/AgentController.java`
- Modify: `backend/ruoyi-admin/pom.xml`
- Create: `backend/ruoyi-train-agent/src/test/java/com/ruoyi/train/agent/web/AgentControllerTest.java`

**Interfaces:** `POST /train/agents/plan`、`POST /train/agents/diagnose`、`POST /train/agents/execution-draft`。Diagnosis controller 从 `JobService.getOwned(jobId, userId, isAdmin)` 后读取限长 logs/metrics/alerts；客户端不可指定 owner id。Execution endpoint 只返回草案，不调用 `JobService.submit`。

- [x] 测试 view/manage 权限注解；诊断 controller 调用既有 `JobService.getOwned` owner/admin 检查，core 既有测试覆盖 owner 与 admin 分支；固定工作流测试确认 Execution 只返回草案。
- [x] 运行测试观察缺少 controller 时失败。
- [x] 添加 agent module 的 core/common 与 web/context 依赖并接入 admin；实现 controller 权限和所有权校验。
- [x] 重跑测试，确认通过。

### Task 4: 固定正确性/安全性评测与文档

**Files:**
- Create: `backend/ruoyi-train-agent/src/test/java/com/ruoyi/train/agent/eval/AgentFixedEvaluationTest.java`
- Create: `docs/verification/phase-5-qwen-workflow.md`
- Modify: `docs/superpowers/plans/2026-10-02-gputrain-agent-roadmap.md`
- Modify: `README.md`

- [x] 固定评测 Planner/Diagnosis/Execution 合法输出及 prompt injection、Shell 请求、证据伪造、无权限、无确认、GPU重复/越界和 schema 污染案例。
- [x] 运行 `mvn -f backend/pom.xml -pl ruoyi-train-agent -am test`，agent 18 项、Runner protocol/core 共 166 项通过；无真实 Qwen 请求。
- [x] 评测通过后更新阶段五路线图/README，记录模型 API 尚需部署密钥与人工 smoke request。
- [x] 运行 `git diff --check`；`mvn -f backend/pom.xml -pl ruoyi-admin -am -DskipTests package` 成功。

## Self-review

- Spec coverage: contracts (Task 1), Qwen adapter (Task 2), permissions/owner checks (Task 3), human confirmation/policy (Tasks 1/3), fixed correctness/security suite (Task 4).
- No phase-five task invokes real training or exposes model tools.
- No external Qwen API key is required for deterministic local tests.
