# 阶段一验收记录：工程基础与 GPUTrain 骨架

## 验收范围

验收日期：2026-10-03。

功能代码基线：`62006c04dfab`，包含 MySQL 初始化字符集与 SQL 执行顺序修复。本文件所在提交为阶段一文档验收提交。

阶段一只验收以下能力：

- 官方 RuoYi Spring Boot 3 后端和 Vue 3 TypeScript 前端可构建。
- 四个 GPUTrain Maven 模块进入根 Reactor。
- MySQL、Redis 可通过 Compose 重复启动。
- 训练总览接口和动态菜单受 `train:overview:view` 权限控制。
- 三卡片训练工作台可加载总览接口。

Runner 执行、GPU 采集与分配、训练日志、Qwen Agent 和 MCP 不在本阶段范围内。

## 验收环境

| 项目 | 版本 |
|---|---|
| macOS | 15.6 |
| Java | OpenJDK 17.0.20.1 |
| Maven | 3.9.16 |
| Node.js | 26.0.0 |
| npm | 11.12.1 |
| Docker Compose | 5.5.1 |
| MySQL | 8.4 |
| Redis | 7.4-alpine |

后端使用 `local` Spring Profile，MySQL 和 Redis 仅映射到宿主机回环地址。数据库密码来自未提交的 `.env` 和 `DB_PASSWORD` 环境变量。

## 自动验证

### 后端

```bash
mvn -f backend/pom.xml clean test
```

结果：退出码 0，11 个 Reactor 模块全部 `SUCCESS`。GPUTrain 和本地配置相关测试共 6 个，0 失败、0 错误：

- `TrainOverviewServiceTest`：1 个。
- `TrainOverviewControllerTest`：2 个。
- `RunnerHeartbeatTest`：1 个。
- `LocalApplicationConfigurationTest`：1 个。
- `LogbackConfigurationTest`：1 个。

### 前端

```bash
npm --prefix frontend test
npm --prefix frontend run build:prod
```

结果：Vitest 1 个测试通过；Vite 6.4.1 转换 2642 个模块并成功生成 `frontend/dist`。

### Compose 与工作区

```bash
docker compose --env-file .env -f deploy/compose.yaml config -q
git diff --check
```

结果：两条命令退出码均为 0。MySQL 与 Redis 容器健康检查均为 `healthy`。

## 数据库初始化验证

MySQL 8.4 容器的命令行客户端默认字符集为 latin1。初始化脚本现显式执行 `SET NAMES utf8mb4`，并按以下顺序映射：

1. `01-ruoyi.sql`
2. `02-quartz.sql`
3. `03-gputrain-menu.sql`

重建本地数据库后，以 utf8mb4 客户端查询得到：

| 数据 | 存储十六进制 |
|---|---|
| 系统管理 | `E7B3BBE7BB9FE7AEA1E79086` |
| 系统监控 | `E7B3BBE7BB9FE79B91E68EA7` |
| 若依 | `E88BA5E4BE9D` |

GPUTrain 顶级目录与训练工作台合计恰好 2 条，证明初始化顺序和幂等条件没有产生重复菜单。

## 运行时权限验证

- 管理员登录后动态路由包含 GPUTrain 和训练工作台，可加载三卡片页面。
- 普通用户 `ry` 的动态路由不包含 GPUTrain。
- 普通用户绕过菜单直接请求 `GET /train/overview`，返回若依业务码 403。
- 未登录请求进入若依安全链并返回业务码 401。

菜单隐藏用于交互体验，后端 `@PreAuthorize` 才是不可绕过的接口授权边界。阶段一没有实现实验归属和团队范围的数据权限。

## 已知限制

- 总览中的运行中实验数和在线 Runner 数仍为 0，来自阶段一占位服务，不代表真实采集结果。
- `ruoyi-train-monitor` 与 `ruoyi-train-agent` 只有模块边界。
- Node.js 26 已通过当前测试和生产构建，但后续依赖升级仍需重新执行兼容性门禁。
- 当前只验证本机开发环境；真实 GPU 服务器验收属于后续阶段。

## 结论

阶段一退出条件已经满足，可以基于已提交的 Runner 协议模块进入阶段二“用户级 Training Runner”。
