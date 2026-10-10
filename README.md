# GPUTrain Agent

GPUTrain 是基于 RuoYi-Vue Spring Boot 3 和 RuoYi-Vue3 TypeScript 的训练实验管理与智能诊断项目。`dev` 开发分支已接入多服务器 SSH 训练路径：控制面连接用户的 Linux 账户，使用 `systemd --user` 启动固定 profile 任务；旧 Runner 模块和历史接口仍保留。当前架构、一次训练的数据链路及验收边界见 [系统设计](docs/architecture/current-system-design.md)，未完成项见 [设计评审](docs/architecture/design-review-2026-10-10.md)，分支关系见 [分支盘点](docs/architecture/branch-inventory-2026-10-10.md)。下文各阶段 Runner 说明记录其历史实现和兼容路径，不等同于新 SSH 路径的部署要求。

阶段一到阶段七已有实现和实机验收证据。2026-10-07 在 Linux H20 目标机部署 MCP Streamable HTTP、验证双用户 owner 隔离/权限/审计，并通过 GPUTrain 托管 3 卡一步训练（job9：loss `3.98971343`、step runtime `26.9208s`、3 个日志块、132 项指标、12 个资源样本）；数据库备份、隔离恢复和临时权限清理均已完成。该结果是功能验收，不是长时间吞吐或模型质量评估。详见 `docs/verification/phase-7-deployment.md`。

## 模块

| 模块 | 当前职责 |
|---|---|
| `ruoyi-train-core` | 总览、Runner 注册与认证、GPU 目录/原子租约/任务状态机，以及日志、指标与告警 API |
| `ruoyi-train-runner-protocol` | 不依赖 Spring 的 Runner 协议与 HMAC 签名 |
| `ruoyi-train-runner` | 独立 Java 17 可执行包，安全执行 fixture 并恢复本地进程状态 |
| `ruoyi-train-monitor` | 监控扩展边界；当前数据库与接口实现位于 core observability 包 |
| `ruoyi-train-agent` | Planner、Diagnosis、Execution 契约、Qwen HTTP adapter、策略门和权限受控草案 API |

后端保持模块化单体部署，避免在团队规模和流量尚不需要时引入微服务运维成本。

## 本地要求

- Java 17
- Maven 3.9+
- Node.js 26（已通过测试和生产构建；依赖不兼容时切换到 22 或 24 LTS）
- Docker Desktop 与 Docker Compose

官方源码来源与提交信息见 `docs/upstream.md`。

## 首次启动

复制本地环境文件并修改密码。`.env` 已被 Git 忽略，不要提交真实凭据。

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/compose.yaml up -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

服务端口：

| 服务 | 地址 |
|---|---|
| 前端开发服务器 | `http://127.0.0.1:5173`，占用时 Vite 自动选择下一端口 |
| 后端 API | `http://127.0.0.1:8080` |
| MySQL | `127.0.0.1:3307` |
| Redis | `127.0.0.1:6380` |

MySQL 首次创建数据目录时按照以下顺序执行初始化脚本：

1. 若依业务表和基础数据。
2. Quartz 表。
3. GPUTrain 菜单与管理员授权。
4. Runner 表、升级逻辑与管理权限。
5. GPU 目录、租约与任务状态表。
6. 日志游标、日志事件、指标和告警表。
7. MCP 调用审计表。

所有 SQL 文件显式执行 `SET NAMES utf8mb4`，避免 MySQL 客户端默认字符集导致中文被错误转码。

## 启动后端

推荐在 IDEA 中运行 `com.ruoyi.RuoYiApplication`，使用 `ruoyi-admin` 模块和 Java 17，并配置：

```text
Active profiles: local
Environment variables: DB_PASSWORD=与.env中MYSQL_PASSWORD相同的值
Working directory: $PROJECT_DIR$/backend/ruoyi-admin
```

也可以在项目根目录使用 Maven：

```bash
SPRING_PROFILES_ACTIVE=local DB_PASSWORD=你的本地数据库密码 \
  mvn -f backend/ruoyi-admin/pom.xml spring-boot:run
```

本地日志默认写入 `backend/ruoyi-admin/logs`，可通过 `LOG_PATH` 修改。

Runner 功能还需要在控制面进程环境中提供 `RUNNER_CREDENTIAL_MASTER_KEY`（Base64 编码的 32 字节随机密钥）及 `RUNNER_ENROLLMENT_PEPPER`（独立随机值）。`.env.example` 中仅列出空字段；Compose 的 `.env` 不会自动注入 IDEA 或 Maven 启动的 Java 进程。生产环境必须使用 HTTPS；`local` Profile 的 HTTP 例外仅限回环地址。

## 用户级 Runner

在项目根目录构建：

```bash
mvn -f backend/pom.xml clean package
```

可执行 JAR 由 `backend/ruoyi-train-runner/target/ruoyi-train-runner-3.9.2.jar` 生成。Runner 以 `java -jar <jar> <absolute-runner.properties-path>` 启动；配置键和无秘密示例见 `deploy/runner/runner.properties.example`。`credentialFile` 保存注册返回的 Base64URL Runner 密钥，仅由目标 Linux 用户读取，权限必须为 `600`。Runner 不读取 Shell 命令、不通过 SSH 运行训练，工作目录和状态日志受 `allowedRoot` 限制。

`deploy/runner/gputrain-runner.service` 使用 `systemd --user`，路径固定为 `/data/hanw/hw_code/gputrain-test`，并以 `KillMode=process` 保留运行中的 fixture 供重启对账。完整的无秘密打包、人工安装和 Linux 验收命令见 `docs/verification/phase-2-runner.md`；本机打包不能代替目标机验收。

停机限制：`KillMode=process` 也会在停止 service 时留下它启动的进程；Runner 重启后当前实现不会恢复进程超时计时器。停机前必须先通过控制面 STOP 所有运行命令并核对 PID 已退出，再执行 `systemctl --user stop/disable`。控制面故障时只按本地状态文件记录的 PID、启动时间、可执行文件、工作目录和参数摘要逐一核验并处置；不能用 `pkill java`。具体的拒绝式人工流程见阶段二验收文档。

## GPU 调度（阶段三）

Runner 通过固定参数数组（不使用 Shell）调用 `nvidia-smi`，周期上报其用户可见的 GPU 快照；控制面用 GPU UUID（而非易变的 ordinal）作为持久身份，校验快照的范围、数量与新鲜度，并保守处理外部进程与身份冲突。固定共享 GPU profile 可在身份明确、快照新鲜且空闲显存达到 profile 门槛时调度被其他进程占用的整卡；调度器不会终止外部进程。

任务提交后，控制面在单个数据库事务内原子分配满足约束的 GPU：生成列唯一约束 `uk_active_gpu` 保证同一 GPU 任一时刻最多一个 ACTIVE 租约；多卡请求要么全得、要么整事务回滚。控制面根据租约 UUID 注入 Runner 命令的 `CUDA_VISIBLE_DEVICES`，并拒绝客户端覆盖；Runner 配置须将它加入 `environmentAllowlist`。Runner 的命令回报与进程对账会同步任务状态，确认成功/失败/停止后按任务释放租约；停止失败则隔离任务并保留租约。任务、命令、租约与进程通过 `job_id` 稳定关联，任务状态机（PENDING/QUEUED/ALLOCATING/ALLOCATED/STARTING/RUNNING/SUCCEEDED/FAILED/STOPPING/STOPPED/QUARANTINED/CANCELED）驱动开始、成功、失败、停止与恢复。

关键安全规则：Runner 注册时绑定创建 enrollment 的用户；任务只会分配给该用户拥有且在线的 Runner 所观察到的 GPU。旧 Runner 的所有者未知时保持不可调度，需先确认归属再绑定。共享 profile 仍拒绝 MIG（阶段三不支持）、过期快照、Runner 离线或身份不确定的 GPU；过期租约不自动等于空闲，必须重新探测并核对进程，否则进入隔离。本地开发机无 NVIDIA GPU；Linux H20 目标机已完成 1-step 真实训练短测，证据见 `docs/verification/phase-3-gpu-scheduling.md`。

## 日志、指标与告警（阶段四）

Runner 按 stdout/stderr 文件分别读取 UTF-8 行边界内的有界字节块，通过 HMAC 接口查询控制面的确认 offset 并上传；控制面在事务中校验 Runner、job、stream 和连续 offset，同 offset 重试幂等。日志历史以数据库 event ID 排序，`GET /train/jobs/{jobId}/logs/stream` 支持 `Last-Event-ID` 恢复，读取接口每次校验任务归属。

训练日志中的 `train_loss`、`learning_rate`、`epoch` 等数值被保存为历史指标；GPU 利用率和显存历史从任务租约期间的 Runner 快照关联。固定规则会为 CUDA OOM、NaN/Inf loss 与 NCCL 错误生成每任务去重的告警，不执行动作或模型调用。升级已有数据库需按阶段顺序执行 `backend/sql/gputrain_phase4.sql`；只在 MySQL 首次初始化的部署会自动执行 `06-gputrain-phase4.sql`。本地测试证据及目标机待办见 `docs/verification/phase-4-observability.md`。

## Qwen 多 Agent 工作流（阶段五）

Planner、Diagnosis 和 Execution 使用 schemaVersion 1 JSON 契约。Execution 只产出 `docowl2-srfund` 训练草案，策略校验权限、step 和 GPU 白名单并要求人工确认；模型没有工具调用、Runner、Shell 或文件系统能力。接口为 `POST /train/agents/plan`、`/diagnose` 和 `/execution-draft`，诊断会先校验任务 owner。配置 `gputrain.agent.qwen.api-key` 或环境变量 `DASHSCOPE_API_KEY`、`gputrain.agent.qwen.model` 和 `gputrain.agent.qwen.base-url` 后可请求兼容接口；默认模型为 `qwen3.8-27b`，无密钥时失败关闭。阶段五固定用例不访问真实模型、不创建训练任务。详细设计和验收记录分别见 `docs/superpowers/specs/2026-10-06-gputrain-agent-phase-5-qwen-workflow-design.md` 与 `docs/verification/phase-5-qwen-workflow.md`。

阶段六在现有 Spring Boot 服务的 `/mcp` 提供 MCP Streamable HTTP，只接受现有 JWT 身份。工具白名单仅包含本人任务/实验列表、摘要、日志、指标、告警和 GPU 资源历史查询；管理员也按任务 owner 隔离。MCP 摘要不返回命令、进程配置或 request key，日志最多返回 100 条。`backend/sql/gputrain_phase6.sql` 创建只含调用者、工具、目标任务、结果码和时间的审计表；升级已有数据库需手工执行该脚本，首次初始化会通过 Compose 自动执行。详见 `docs/superpowers/specs/2026-10-06-gputrain-agent-phase-6-mcp-design.md` 和 `docs/verification/phase-6-mcp.md`。

## 部署与恢复（阶段七）

使用 `docs/operations/deployment-and-recovery.md` 执行预检、备份、数据库迁移、发布、回滚和 Runner 安全停机；初次运行 Compose 后按服务配置启动控制面和用户级 Runner。共享 GPU 时按任务配置与每卡可用显存阈值决定是否提交，不以利用率单独判断，并且不干预其他用户进程。`scripts/demo-phase7-mcp.sh` 仅查询 overview、MCP 工具目录和当前用户任务，不创建或停止任务。项目简历描述位于 `docs/project-summary/resume-copy.md`。本次目标服务只在 loopback 提供 HTTP；HTTPS 反向代理和 IDE 客户端实配未验收。

## 启动前端

本机开发、后端部署在 GPU 服务器时，将 `frontend/.env.example` 复制为被 Git 忽略的
`frontend/.env.local`，把 `GPUTRAIN_CONTROL_PLANE_URL` 改为服务器控制面的 HTTPS origin。
本地 Vite 仍通过 `/dev-api` 代理请求。更换目标后需重启 Vite 并重新登录。
远程 Runner 主动连接同一控制面；前端页面不直接 SSH 登录服务器。

```bash
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5173
```

如果终端已经位于 `frontend/`，直接执行 `npm run dev -- --host 127.0.0.1 --port 5173`，不要再次添加 `--prefix frontend`。

## 验证

```bash
mvn -f backend/pom.xml clean test
mvn -f backend/pom.xml -pl ruoyi-train-core test -Pmysql-it
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build:prod
docker compose --env-file .env.example -f deploy/compose.yaml config -q
git diff --check
```

管理员可以看到 `GPUTrain / 训练工作台` 并访问 `GET /train/overview`；未授权用户既看不到菜单，直接请求接口也会被拒绝。

完整设计见 `docs/superpowers/specs/2026-10-02-gputrain-agent-design.md`，阶段路线图见 `docs/superpowers/plans/2026-10-02-gputrain-agent-roadmap.md`，阶段一验收证据见 `docs/verification/phase-1-foundation.md`。阶段二本地证据与远程待验项目见 `docs/verification/phase-2-runner.md`。阶段三验收与真实 GPU 训练短测证据见 `docs/verification/phase-3-gpu-scheduling.md`。
