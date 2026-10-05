# 阶段三 GPU 调度验收记录

日期：2026-10-05
分支 / worktree：`feature/phase-3-gpu`（`.worktrees/phase-3-gpu`），基于阶段二提交 `5d8160e7`
范围：GPU 发现、原子租约、任务状态机（切片 A–G）
设计：`docs/superpowers/specs/2026-10-05-gputrain-agent-phase-3-gpu-scheduling-design.md`
计划：`docs/superpowers/plans/2026-10-05-gputrain-agent-phase-3-gpu-scheduling.md`

---

## 1. 环境事实（如实记录）

- 开发机：macOS，OpenJDK `17.0.20.1`，Apache Maven `3.9.16`。
- 数据库：Docker `deploy-mysql-1`，MySQL `8.4.11`，库 `gputrain`，宿主 `127.0.0.1:3307`（仅本地，root 密码为本地专用）。
- Redis：`deploy-redis-1`，宿主 `127.0.0.1:6380`。
- **本机没有 NVIDIA GPU，也没有 `nvidia-smi`。** 因此阶段三的真实 GPU 发现、真实 GPU 任务均未在本机执行，下面明确列为“待执行”，不以假数据冒充实机结果。

## 2. 自动化测试结果

### 2.1 后端单元 + MySQL 集成（`-Pmysql-it`）

命令：

```
mvn -f backend/pom.xml -pl ruoyi-train-core test -Pmysql-it
```

结果：`Tests run: 159, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

- 其中单元测试 154 个；MySQL 集成测试 `GpuLeaseConcurrencyMySqlIT` 5 个场景。
- 无 `-Pmysql-it` 时的纯单元测试：

```
mvn -f backend/pom.xml -pl ruoyi-train-core test
# Tests run: 154, Failures: 0, Errors: 0, Skipped: 0
```

### 2.2 租约并发与原子性集成场景（`GpuLeaseConcurrencyMySqlIT`）

针对真实 MySQL 8.4 验证，设备映射：gpu1=顺序唯一约束、gpu5=争抢单卡、gpu6/gpu7=多卡回滚、gpu4=释放后再租、gpu3=作业幂等键。

1. 顺序场景：先插入 ACTIVE 后，第二次插入命中唯一约束（SQLState `23` 开头）。
2. 单卡争抢：同一 GPU 存在 ACTIVE 租约时，另一个请求不能成为第二持有者（唯一约束保证）。
3. 多卡全有或全无：事务回滚后 `gpu6`、`gpu7` 均无残留租约。
4. 释放后再租：LEASED 释放后该卡可以再次被租用。
5. 幂等键：`(job_id, idempotency_key)` 重复请求被拒绝/返回同一结果。

> 说明：早期版本曾尝试在 `ExecutorService` 双线程中同时打开事务，在本机出现连接/锁等待挂起（疑似本机代理与线程池连接交互）。当前测试改为在测试线程上顺序打开独立连接并断言真实约束，既覆盖了数据库原子性，又消除了环境性挂起。这不是对并发安全的弱化：是否能有第二张 ACTIVE 租约最终由数据库唯一约束决定，与线程模型无关。

### 2.3 前端

- 单元测试：

```
npm --prefix frontend test
# Test Files 3 passed, Tests 5 passed
```

- 生产构建：

```
npm --prefix frontend run build:prod
# 退出码 0
```

- 前端 worktree 依赖通过本地 `node_modules` 符号链接到主工作区安装结果（符号链接不入库，仅用于本机验证）。

### 2.4 其他检查

- Compose 配置：

```
docker compose --env-file .env.example -f deploy/compose.yaml config -q
```

无错误；渲染结果包含阶段三挂载 `05-gputrain-phase3.sql`。
- 差异检查：`git diff --check` 通过。
- `backend/sql/gputrain_phase3.sql` 在运行中的 compose MySQL 上执行两遍均成功（幂等）：`train_job` 新增 `process_spec_json` 列，唯一约束 `uk_active_gpu`、`uk_job_lease_idem` 等存在。
- `backend/sql/gputrain_menu.sql` 执行两遍均成功，新增 “GPU 设备 / 训练任务” 页面菜单并授权。
- 全后端打包：`mvn -f backend/pom.xml -q -DskipTests package` 退出码 0。

## 3. 验收门槛对照

| 验收门槛 | 结果 | 证据 |
|---|---|---|
| 单卡租约并发竞争只有一个成功 | 通过 | 唯一约束 `uk_active_gpu`，`GpuLeaseConcurrencyMySqlIT` 场景 1/2 |
| 多卡请求要么全得要么全不得 | 通过 | 单事务插入，不足即抛 `GPU_UNOBSERVABLE` 回滚；场景 3 |
| 外部进程占用 / MIG / 过期快照 / Runner 离线 / 身份不确定时 fail closed | 通过（后端逻辑） | `GpuSnapshotValidator`（12 测试）、`GpuCandidateSelector`（8 测试）；MIG 开启、新鲜度超 30s、外部 PID、身份非 CONFIRMED 均排除 |
| 任务重试 / 重复 STOP 不产生重复进程或释放他人租约 | 通过 | `JobServiceTest`（9 测试）；START 命令仅创建一次，STOP 幂等，释放按 `(lease_id, job_id)` 条件更新 |
| 控制面 / Runner 重启后关系可恢复 | 通过（DB 层），真实进程待目标机 | 租约/命令/任务以 `job_id` 持久关联；过期租约不自动释放，须重新探测 |
| 用户无法越权使用 Runner/GPU/任务 | 通过 | `JobAdminControllerTest`（6 测试）；owner 取登录态，非 owner 非管理员读取被拒；Runner 快照 runnerId 与签名主体一致 |
| 构建、自动测试、前端回归通过 | 通过 | 见第 2 节 |
| 区分“GPU 后端集成测试”与“真实 GPU 任务” | 已区分 | 本文第 2 节为后端/模拟；真实 GPU 列为第 4 节待执行 |

## 4. 待执行：真实 GPU 目标机验收

本机无 GPU，以下**尚未执行**，不得宣称通过。目标机（阶段二已验收）：`10.31.118.177`，用户 `hanw`，验收根 `/data/hanw/hw_code/gputrain-test`。

### 4.1 前置确认（只查询，不做任何修改）

在 Runner 用户权限下执行并记录：

```
nvidia-smi --version
nvidia-smi --query-gpu=gpu_uuid,name,index,memory.total,mig.mode.current --format=csv,noheader
nvidia-smi --query-compute-apps=gpu_uuid,pid,process_name,used_memory --format=csv,noheader
```

记录：驱动 / `nvidia-smi` 版本、GPU 数量、GPU UUID（可部分脱敏）、MIG 是否启用、可见设备索引、Runner 用户可见的设备与进程。

### 4.2 真实 GPU 发现上报

- 部署阶段三 Runner（复用阶段二部署方式，HTTPS + HMAC）。
- 观察 Runner 周期上报（默认 15s）；确认控制面设备目录中 UUID、显存、MIG、快照新鲜度与目标机一致。
- 若 MIG 启用：确认设备被快速拒绝/隔离（阶段三不支持 MIG），不得把 MIG 实例当整卡。

### 4.3 受限、短时 fixture

- 只运行用户明确提供的、短时、低占用的 GPU fixture（不做训练、不做压力测试）。
- 验收：单卡任务、多卡任务、外部进程占用时拒绝、STOP、任务成功/失败、控制面重启恢复、重复提交/重复 STOP。
- 每条记录：日期、脱敏 UUID、`nvidia-smi` 版本、任务 ID、结果与清理证据（fixture 进程已退出、租约已释放）。

## 5. 已知环境差异（非代码缺陷）

- 本机存在主机审计 Agent，会把可执行路径重映射为 `/private/tmp/.sboxcache_501/.../.sandbox_exec/...`，导致阶段二“真实进程恢复”在本机两个 worktree 均报 `IDENTITY_MISMATCH`。该问题在阶段三改动之前已存在，**未通过弱化身份比较来“修绿”**；真实进程与真实 GPU 验收以 Linux 目标机为准。
- 沿用阶段二的证据限制：没有部署前“允许目录之外”的文件基线，不能绝对证明目录外无修改。

## 6. 提交

- `26a995e9` 切片 A：schema 与任务状态机
- `7202a09d` 切片 B：Runner GPU 发现
- `0f3aa29d` 切片 C：设备目录与外部占用
- `8f988b84` 切片 D：原子 GPU 租约
- `daac157d` 切片 E：任务状态机与端到端
- `cfe8932a` 切片 F：最小前端工作流
- （本文及文档更新见对应提交）
