# GPUTrain Agent

面向一台由团队共享的多 GPU 服务器的训练实验管理与智能诊断平台。控制面基于 RuoYi-Vue Spring Boot 3 和 RuoYi-Vue3 TypeScript；训练任务计划由每个 Linux 账户下的原生 Runner 执行，以保留用户文件权限、Conda 环境和 CUDA 工具链。

当前完成阶段一：工程基础、模块边界、可复现依赖、权限控制和训练工作台。Runner、GPU 调度、日志监控、Qwen Agent 与 MCP 尚未实现，不应将它们当作现有能力。

## 模块

| 模块 | 当前职责 |
|---|---|
| `ruoyi-train-core` | 训练领域入口和受保护的总览接口 |
| `ruoyi-train-runner-protocol` | 不依赖 Spring 的 Runner 协议对象 |
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

## 启动前端

```bash
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5173
```

如果终端已经位于 `frontend/`，直接执行 `npm run dev -- --host 127.0.0.1 --port 5173`，不要再次添加 `--prefix frontend`。

## 验证

```bash
mvn -f backend/pom.xml clean test
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env -f deploy/compose.yaml config -q
git diff --check
```

管理员可以看到 `GPUTrain / 训练工作台` 并访问 `GET /train/overview`；未授权用户既看不到菜单，直接请求接口也会被拒绝。

完整设计见 `docs/superpowers/specs/2026-10-02-gputrain-agent-design.md`，阶段路线图见 `docs/superpowers/plans/2026-10-02-gputrain-agent-roadmap.md`，阶段一验收证据见 `docs/verification/phase-1-foundation.md`。
