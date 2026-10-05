# GPUTrain Agent

面向一台由团队共享的多 GPU 服务器的训练实验管理与智能诊断平台。控制面基于 RuoYi-Vue Spring Boot 3 和 RuoYi-Vue3 TypeScript；训练任务计划由每个 Linux 账户下的原生 Runner 执行，以保留用户文件权限、Conda 环境和 CUDA 工具链。

阶段一工程基础、阶段二用户级 Runner 与阶段三 GPU 调度（GPU 发现、原子租约、任务状态机）代码已实现。阶段二已在 Linux 目标机完成 fixture 级验收；阶段三的后端与确定性测试已在本地完成，真实 GPU 验收需在带 NVIDIA GPU 的目标机执行。日志/SSE/指标告警、Qwen Agent 与 MCP 仍属于后续阶段。

## 模块

| 模块 | 当前职责 |
|---|---|
| `ruoyi-train-core` | 总览、Runner 注册与认证、命令租约、状态上报、GPU 目录/原子租约/任务状态机 |
| `ruoyi-train-runner-protocol` | 不依赖 Spring 的 Runner 协议与 HMAC 签名 |
| `ruoyi-train-runner` | 独立 Java 17 可执行包，安全执行 fixture 并恢复本地进程状态 |
| `ruoyi-train-monitor` | 监控边界，阶段一仅建立模块 |
| `ruoyi-train-agent` | Agent 边界，阶段一不接入模型 |

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

Runner 通过固定参数数组（不使用 Shell）调用 `nvidia-smi`，周期上报其用户可见的 GPU 快照；控制面用 GPU UUID（而非易变的 ordinal）作为持久身份，校验快照的范围、数量与新鲜度，并保守处理外部进程与身份冲突。

任务提交后，控制面在单个数据库事务内原子分配满足约束的 GPU：生成列唯一约束 `uk_active_gpu` 保证同一 GPU 任一时刻最多一个 ACTIVE 租约；多卡请求要么全得、要么整事务回滚。任务、命令、租约与进程通过 `job_id` 稳定关联，任务状态机（PENDING/QUEUED/ALLOCATING/ALLOCATED/STARTING/RUNNING/SUCCEEDED/FAILED/STOPPING/STOPPED/QUARANTINED/CANCELED）驱动开始、成功、失败、停止与恢复。

关键安全规则：外部进程占用、MIG 开启（阶段三不支持 MIG）、快照过期、Runner 离线或身份不确定时一律 fail closed；过期租约不自动等于空闲，必须重新探测并核对进程，否则进入隔离。本地开发机无 NVIDIA GPU，真实 GPU 验收步骤见 `docs/verification/phase-3-gpu-scheduling.md`。

## 启动前端

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
npm --prefix frontend run build:prod
docker compose --env-file .env.example -f deploy/compose.yaml config -q
git diff --check
```

管理员可以看到 `GPUTrain / 训练工作台` 并访问 `GET /train/overview`；未授权用户既看不到菜单，直接请求接口也会被拒绝。

完整设计见 `docs/superpowers/specs/2026-10-02-gputrain-agent-design.md`，阶段路线图见 `docs/superpowers/plans/2026-10-02-gputrain-agent-roadmap.md`，阶段一验收证据见 `docs/verification/phase-1-foundation.md`。阶段二本地证据与远程待验项目见 `docs/verification/phase-2-runner.md`。阶段三验收证据与真实 GPU 待验步骤见 `docs/verification/phase-3-gpu-scheduling.md`。
