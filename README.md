# GPUTrain Agent

面向共享多GPU服务器的训练实验管理与智能诊断平台。项目以RuoYi-Vue Spring Boot 3和RuoYi-Vue3 TypeScript为基础，当前处于阶段一工程骨架建设。

## 本地要求

- Java 17+
- Maven 3.9+
- Node.js 26（已通过当前若依前端生产构建；若后续依赖出现兼容问题则切换到22或24 LTS）
- Docker Desktop与Docker Compose

## 启动基础依赖

复制 `.env.example` 为不会提交的 `.env` 并修改本地密码，然后运行：

```bash
docker compose --env-file .env -f deploy/compose.yaml up -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

MySQL监听 `127.0.0.1:3307`，Redis监听 `127.0.0.1:6380`。

使用本地配置启动后端（密码应与本机 `.env` 中的 `MYSQL_PASSWORD` 一致）：

```bash
SPRING_PROFILES_ACTIVE=local DB_PASSWORD=你的本地数据库密码 \
  mvn -f backend/ruoyi-admin/pom.xml spring-boot:run
```

本地日志默认写入 `backend/ruoyi-admin/logs`，可以通过 `LOG_PATH` 指定其他目录。停止基础依赖：

```bash
docker compose --env-file .env -f deploy/compose.yaml down
```

## 基线构建

```bash
mvn -f backend/pom.xml test
npm --prefix frontend install
npm --prefix frontend run build:prod
```

官方源码来源与提交号见 `docs/upstream.md`，完整设计见 `docs/superpowers/specs/2026-10-02-gputrain-agent-design.md`。
