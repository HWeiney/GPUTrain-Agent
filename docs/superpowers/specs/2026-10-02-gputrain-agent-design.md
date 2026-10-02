# GPUTrain Agent 设计规格

## 1. 项目定位

GPUTrain Agent 是面向实验室共享多 GPU 服务器的训练实验管理与智能诊断平台。系统解决团队成员通过 VSCode 远程登录服务器、手动选择 GPU、拼接 `torchrun` 命令、查看日志并借助通用 AI 排查故障时存在的资源冲突、记录分散、诊断低效和操作风险问题。

项目以训练管理系统为主体，以多 Agent 作为自然语言规划、故障诊断和执行决策入口。Agent 不直接持有 root 权限，也不执行任意 Shell；所有有副作用的操作均经过 Java 规则校验、用户确认和用户级 Runner 执行。

### 1.1 项目目标

- 支持单台 Linux 服务器上的多 GPU、多 Linux 用户协作。
- 统一管理实验配置、GPU 预约、训练任务、实时日志、指标、告警和 checkpoint 元数据。
- 支持自然语言创建实验、分析故障和生成受约束的执行计划。
- 使用多 Agent 分工完成意图路由、实验规划、故障诊断和执行决策。
- 通过 MCP 将平台能力安全开放给 IDE Agent，并为外部工具提供受控扩展机制。
- 使用若依构建权限、菜单、数据隔离、审计和后台管理能力。
- 在开发过程中同步沉淀与真实代码对应的面试考点。

### 1.2 非目标

首版不实现多服务器调度、GPU 抢占和时间片、Kubernetes 训练、自动超参数搜索、多租户计费、完整 MLflow 替代品、自动删除 checkpoint，以及 Agent 自主执行 root 命令。

## 2. 技术选型

### 2.1 应用技术栈

- 后端：Java 17、RuoYi-Vue Spring Boot 3、Spring Security、MyBatis、Spring AI。
- 前端：RuoYi-Vue3 TypeScript、Vue 3、Vite、Element Plus、Pinia。
- 数据：MySQL 作为持久化事实来源，Redis 保存心跳、短期上下文、监控快照和流式游标。
- AI：Qwen 在线 API，使用结构化输出和工具调用。
- 监控：NVIDIA DCGM Exporter、Prometheus、Grafana。
- 部署：Docker Compose 部署控制面及依赖，用户级 Runner 和训练进程原生运行。
- 通信：Runner 主动注册、心跳、长轮询领取任务并上报状态；浏览器通过 SSE 获取日志和 Agent 进度。

### 2.2 不采用的技术

- 首版不使用 Kafka：单服务器和有限任务量下，数据库任务表、线程池和 Runner 拉取已满足可靠性要求。
- 首版不使用 Elasticsearch：完整日志保存在用户目录，MySQL 只保存元数据和摘要。
- 不使用 RuoYi-Cloud：模块化单体足以满足当前规模，避免引入注册中心、网关和分布式事务。
- 不将现有 Conda 训练任务强制容器化：保留用户文件权限、CUDA 兼容性和调试习惯。

## 3. 总体架构

系统采用“模块化单体控制面 + 用户级 Runner”的结构。

```text
Vue 3 管理端
      |
RuoYi Spring Boot 控制面
      |-- 用户、角色、菜单与数据权限
      |-- 实验、GPU 预约与训练任务
      |-- 多 Agent 编排与共享上下文
      |-- MCP 与工具权限
      |-- 日志、指标、告警与审计
      |
      |-- Qwen API
      |-- MySQL / Redis
      |-- Prometheus / DCGM Exporter
      |
用户级 Training Runner
      |-- 用户 A Runner
      |-- 用户 B Runner
      `-- 用户 C Runner
             |
      torchrun / Python / 日志 / checkpoint
```

建议业务模块：

```text
ruoyi-train-core
ruoyi-train-agent
ruoyi-train-monitor
ruoyi-train-runner-protocol
```

控制面不以 root 身份运行。管理员权限仅用于安装统一 Runner、配置 systemd、安装 DCGM Exporter 和设置必要的系统目录权限。

## 4. 用户级 Training Runner

管理员将统一 Runner 安装到 `/opt/gputrain/runner.jar`。每位成员通过 `systemd --user` 在自己的 Linux 账号下运行 Runner，因此训练进程、日志、环境和 checkpoint 仍归属于本人。

Runner 负责：

- 注册身份并发送心跳。
- 长轮询领取属于当前用户的执行任务。
- 校验允许目录、环境、入口脚本和参数。
- 通过 `ProcessBuilder` 启动训练，不接受任意 Shell 字符串。
- 记录 PID、进程组、日志路径、启动时间和退出码。
- 停止本人启动的任务并保证操作幂等。
- 上报增量日志、进程状态和 checkpoint 元数据。
- 重启后重新关联仍存活的训练进程。

Runner 请求使用独立机器凭证，并绑定若依用户、Linux 用户名和允许目录。请求包含时间戳、随机数和签名，防止伪造与重放。

## 5. GPU 资源与租约

GPU 可用性同时取决于物理状态、平台租约和训练任务状态。

### 5.1 GPU 状态

- `AVAILABLE`：无物理进程且无有效租约。
- `RESERVED`：已预约但尚未启动。
- `PLATFORM_BUSY`：被平台训练任务占用。
- `EXTERNAL_BUSY`：存在平台之外的手动训练进程。
- `UNKNOWN`：Runner 或状态暂时不可确认。
- `UNHEALTHY`：GPU 健康、温度或硬件状态异常。

系统只自动分配 `AVAILABLE` GPU，不终止外部进程。

### 5.2 GPU 选择规则

1. 过滤显存不足、已租约、外部占用和不健康 GPU。
2. 优先选择连续编号、同型号和同显存容量 GPU。
3. 有拓扑信息时优先选择互联更好的组合。
4. 候选相同时选择当前负载和温度更低的组合。

Agent可以解释推荐原因，但最终可用性由 Java 资源服务判定。

### 5.3 租约与并发

GPU 租约包含实验、Runner、用户、GPU 集合、状态、过期时间、版本号和幂等键。生命周期为：

```text
PENDING -> ACTIVE -> RELEASING -> RELEASED
                  `-> EXPIRED
```

MySQL 是正确性边界。多卡申请在事务内按 GPU 编号排序后使用 `SELECT ... FOR UPDATE` 加锁，再次检查状态并创建租约；数据库唯一约束负责最终兜底。Redis用于缓存高频快照、Runner心跳和防重复提交，不作为租约事实来源。

Runner 断线时先将任务标记为未知，在宽限期后重新核验真实进程，避免旧训练仍存活时重复分配 GPU。

## 6. 训练任务状态机

正常流程：

```text
DRAFT -> VALIDATING -> WAITING_RESOURCE -> RESERVED
      -> WAITING_CONFIRMATION -> STARTING -> RUNNING -> SUCCEEDED
```

异常分支包括：

```text
VALIDATING -> REJECTED
STARTING   -> START_FAILED
RUNNING    -> FAILED
RUNNING    -> STOPPING -> STOPPED
RUNNING    -> UNKNOWN -> RUNNING / FAILED
```

任务使用版本号进行乐观锁更新。启动请求携带幂等键，控制面和 Runner 均保存处理结果，确保网络重试不会重复启动训练。Runner 重连时上报存活任务，控制面基于任务ID、PID、启动时间和日志位置完成状态对账。

## 7. 多 Agent 协作

### 7.1 Agent 角色

- `Coordinator Agent`：识别意图、路由任务并汇总专家结果。
- `Experiment Planner Agent`：将自然语言转换为结构化 `ExperimentSpec`。
- `Training Diagnosis Agent`：调用日志、指标、进程和配置工具形成带证据诊断。
- `Execution Agent`：在实验计划和资源结果基础上生成结构化 `ExecutionPlan`。

训练启停由 Java `PolicyEngine` 和 Runner 执行，不设置拥有任意命令权限的 Executor Agent。

### 7.2 Agent 工作流

```text
自然语言请求
  -> Coordinator 路由
  -> Planner 生成实验计划
  -> Resource Service 检查并租约 GPU
  -> Execution Agent 生成执行计划
  -> Policy Engine 校验
  -> 用户确认
  -> Runner 执行
```

诊断流程并行获取日志、GPU指标、系统资源、退出码和实验配置，再由 Diagnosis Agent 形成包含故障类型、置信度、证据、可能原因和建议动作的结构化结果。

### 7.3 共享上下文

`AgentTaskContext` 保存任务、用户、实验、意图、阶段、资源快照、证据、Agent输出、建议动作、审批状态、Token用量和版本号。MySQL持久化关键阶段和结论，Redis保存短期对话和临时工具结果。完整日志不进入上下文，只传递受限窗口、摘要或引用。

每个Agent拥有独立Prompt、最小工具集合、最大调用轮数、超时和Token预算。模型不可用时，手动实验管理和规则告警仍须正常工作。

## 8. Execution Agent 与 Policy Engine

Execution Agent只能生成结构化字段，包括 Runner、工作目录、环境、启动器、GPU列表、入口脚本、参数和checkpoint，不得返回任意Shell命令。

Policy Engine 检查：

- 用户与 Runner 归属。
- 工作目录和入口脚本白名单。
- GPU 状态、预约和显存约束。
- 参数字符、范围和类型。
- checkpoint 路径归属和完整性。
- 分布式端口占用。
- 输出目录覆盖风险。
- 操作风险等级、审批和幂等键。

工具风险分为 `READ_ONLY`、`DRAFT_ONLY`、`CONFIRM_REQUIRED`、`ADMIN_REQUIRED` 和 `FORBIDDEN`。启动、停止和恢复训练需要用户确认；任意Shell和系统文件删除永久禁止开放给Agent。

## 9. MCP 设计

### 9.1 内置工具

平台内的Qwen Agent使用Spring AI工具直接调用Java业务服务，例如查询GPU、获取实验、读取受限日志窗口、查询指标、验证实验计划和创建执行草稿。

### 9.2 对外 MCP Server

平台提供MCP Server，使VSCode中的IDE Agent可以查询：

- 当前GPU占用。
- 本人运行中的实验。
- 实验最近日志和指标。
- 可恢复的checkpoint。
- 故障诊断结果。
- 实验草稿。

对外MCP默认只开放只读和草稿能力。危险操作返回审批请求，不直接执行。每个MCP Token绑定用户、作用域和有效期，所有调用进入审计日志。

### 9.3 外部 MCP 注册

管理员可配置外部MCP Server的名称、传输方式、端点、凭证引用、超时、允许工具、风险等级和允许Agent。工具发现后默认禁用，必须经过白名单授权。潜在扩展包括Git、MLflow、团队通知、外部知识库和其他服务器监控。

## 10. 监控、日志与告警

### 10.1 监控

DCGM Exporter向Prometheus提供GPU利用率、显存、温度、功耗和健康指标。Runner补充PID、Linux用户、平台任务映射和外部进程信息。控制面将两条数据链路合并用于当前状态和历史曲线。

训练指标支持通用日志、JSON Lines和Hugging Face常见格式，通过 `MetricParser` 接口扩展。TensorBoard或MLflow适配作为后续增强。

### 10.2 日志

Runner分别保存stdout、stderr、元数据和退出码。增量日志携带任务、流类型、当前offset和下一offset；控制面通过SSE推送。系统处理断线续传、重复上报、慢客户端、块大小、日志轮转和敏感信息脱敏。完整日志保存在用户目录，MySQL只保存元数据、异常摘要和索引信息。

### 10.3 告警

确定性规则检测OOM、NaN、GPU空转、多卡不均衡、磁盘不足、NCCL异常、训练停滞、温度过高、Runner断线和异常退出。规则引擎负责发现，Diagnosis Agent负责结合证据解释。Qwen不可用时基础告警不受影响。

## 11. Checkpoint 管理

平台只管理checkpoint的路径、实验、step、时间、大小、完整性、最佳模型标记、代码commit、训练参数和最近使用时间，不复制模型文件。

恢复训练前校验路径归属、存在性和完整性。恢复操作创建新的Training Job，不覆盖原任务，以保留审计和对比关系。

## 12. 前端页面与权限

主要页面：

- 工作台：GPU、运行实验、等待任务、告警和Runner状态。
- GPU资源：实时状态、用户进程、预约时间线、候选组合和历史曲线。
- 实验管理：创建、复制、启动、对比和历史记录。
- 实验详情：日志、指标、参数、checkpoint、Agent诊断和操作记录。
- Agent工作台：阶段、工具调用摘要、证据、建议和待确认操作。
- GPU预约：预约、团队日历、等待队列和冲突提示。
- Runner管理：用户、版本、心跳、任务、允许目录和凭证。
- MCP工具管理：连接、发现、白名单、风险、Agent授权和审计。
- 告警与审批：异常、诊断、危险操作和执行记录。

角色分为普通成员、团队管理员和系统管理员。除若依菜单与按钮权限外，Service层必须校验数据归属，防止通过修改资源ID读取其他用户日志或checkpoint。

## 13. 部署

Docker Compose部署前端、Nginx、Spring Boot控制面、MySQL、Redis、Prometheus和Grafana；DCGM Exporter可按服务器条件选择原生或容器方式。Runner、Conda环境和训练进程原生运行。

Runner主动连接控制面，避免保存SSH密码或私钥，也避免控制面使用root切换用户。该协议保留未来扩展至多服务器的可能，但首版只验收单服务器。

## 14. 测试策略

### 14.1 单元测试

- GPU候选组合和状态合并。
- 状态机合法迁移。
- ExecutionPlan和路径校验。
- 日志offset去重与轮转。
- 告警规则和工具风险分级。
- Agent结构化输出解析。
- 数据权限判断。

### 14.2 集成测试

使用Testcontainers运行MySQL和Redis，覆盖多GPU租约事务、并发申请、幂等启动、Runner心跳、审批和权限隔离。Prometheus和Qwen使用可控替身，常规测试不依赖真实外部服务。

### 14.3 Runner测试

抽象 `GpuProvider`、`ProcessExecutor`、`FileSystemGateway` 和 `EnvironmentProvider`，单元测试使用Fake实现。真实服务器端到端测试覆盖单卡、双卡torchrun、停止、OOM、Runner终止、控制面重启和外部进程占卡。

### 14.4 Agent评测

建立正常启动、多卡启动、显存不足、外部占用、OOM、NaN、NCCL超时、磁盘不足、checkpoint缺失和Prompt Injection案例集。统计意图识别、参数提取、工具选择、故障识别、危险操作拦截、工具调用次数、Token和诊断耗时。

## 15. 开发阶段

1. 若依基础工程、权限和Docker基础依赖。
2. Runner、身份认证、心跳、任务领取和进程管理。
3. GPU资源、预约租约、状态机和并发控制。
4. 日志、训练指标、Prometheus、DCGM、告警和checkpoint。
5. Qwen、多Agent、共享上下文、工具调用和审批。
6. MCP Server、IDE集成、外部MCP注册和调用审计。
7. 完整部署、故障评测、README、演示脚本和简历描述。

每个阶段遵循“明确需求与考点、测试设计、最小实现、运行验证、代码走读、更新面试文档”的闭环。

## 16. 首版验收标准

- 若依用户可登录，菜单、按钮和数据权限生效。
- 每个Linux用户可运行独立Runner并完成安全注册。
- 系统正确展示GPU、外部进程和平台任务。
- 并发申请相同GPU时只产生一个有效租约，多卡申请保持原子性。
- 用户可创建、确认、启动、停止和恢复训练任务。
- Runner和控制面重启后可完成任务状态对账。
- 页面可实时查看日志、训练指标和GPU曲线，并支持断线续传。
- 规则可检测典型训练异常，Agent可输出带证据诊断。
- Agent不能绕过Policy Engine或用户审批执行危险操作。
- IDE Agent可通过MCP查询当前用户的GPU和实验信息。
- 控制面可通过Docker Compose部署，训练进程保持用户级原生运行。
- 简历中的所有效果数据均来自测试或真实使用记录。

## 17. 面试资料约定

面试资料单独维护在 `docs/interview/项目面试考点.md`。每个考点包含问题、核心回答、项目实现、关键代码位置、常见追问和易错点。开发阶段完成后立即更新对应章节，使回答始终能够由代码、测试和演示证据支撑。
