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
- **本机没有 NVIDIA GPU，也没有 `nvidia-smi`。** 真实 GPU 发现和 1-step DocOwl2-SRFUND 训练短测已于 2026-10-06 在 Linux 目标机完成；本记录区分真实硬件结果与本机自动化测试。

## 2. 自动化测试结果

### 2.1 后端单元测试与 MySQL 集成状态

命令：

```
mvn -f backend/pom.xml -pl ruoyi-train-core test
```

本次实现后的当前工作区：核心模块自动测试 `161` 通过，0 失败；Runner `61` 项、协议 `20` 项均通过。MySQL 专用集成场景仍需在可连接集成数据库的环境单独执行。

MySQL 集成测试 `GpuLeaseConcurrencyMySqlIT` 包含 5 个场景，其中单卡争抢用两个独立连接和同步屏障并发尝试。此前阶段三基线执行 `-Pmysql-it` 的汇总为 `Tests run: 162, Failures: 0, Errors: 0, Skipped: 5`；这 5 项 MySQL 场景因当时无法连接集成数据库而明确跳过，不能算 MySQL 集成通过。本次代码变更后未重新运行 MySQL 专用场景；目标机测试前应在可访问 MySQL 的环境运行该类并确认 5 项实际执行、0 跳过。

纯单元测试命令：

```
mvn -f backend/pom.xml -pl ruoyi-train-core test
# 本次变更后：Tests run: 161, Failures: 0, Errors: 0, Skipped: 0
```

### 2.2 租约并发与原子性集成场景（`GpuLeaseConcurrencyMySqlIT`）

针对真实 MySQL 8.4 验证，设备映射：gpu1=顺序唯一约束、gpu5=争抢单卡、gpu6/gpu7=多卡回滚、gpu4=释放后再租、gpu3=作业幂等键。

1. 顺序场景：先插入 ACTIVE 后，第二次插入命中唯一约束（SQLState `23` 开头）。
2. 并发单卡争抢：两个独立连接由屏障同时开始插入同一 GPU 的 ACTIVE 租约，恰好一个事务提交，另一个命中唯一约束；最终仅有一个持有者。
3. 多卡全有或全无：事务回滚后 `gpu6`、`gpu7` 均无残留租约。
4. 释放后再租：LEASED 释放后该卡可以再次被租用。
5. 幂等键：`(job_id, idempotency_key)` 重复请求被拒绝/返回同一结果。

> 并发竞争测试使用独立 JDBC 连接和同步屏障，真正同时尝试获取同一设备；这验证数据库唯一约束在竞争事务中的实际行为。多卡回滚另由独立事务测试覆盖。

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
| MIG / 过期快照 / Runner 离线 / 身份不确定时 fail closed；共享模式按实时空闲显存门槛调度 | 通过（后端逻辑 + 目标机实测） | `GpuSnapshotValidator`、`GpuCandidateSelector`；共享模式允许有外部进程的已识别整卡，但要求新鲜快照和最低空闲显存；MIG/身份冲突仍排除 |
| 任务重试 / 重复 STOP 不产生重复进程或释放他人租约 | 通过 | `JobServiceTest`（9 测试）；START 命令仅创建一次，STOP 幂等，释放按 `(lease_id, job_id)` 条件更新 |
| 控制面 / Runner 重启后关系可恢复 | 通过（DB 层），真实进程待目标机 | 租约/命令/任务以 `job_id` 持久关联；过期租约不自动释放，须重新探测 |
| 用户无法越权使用 Runner/GPU/任务 | 逻辑与单元覆盖 | owner 取登录态，分配候选只包含 `train_runner.owner_user_id` 匹配且 Runner 在线的设备；旧未绑定 Runner 不可调度；非 owner 任务读取被拒。仍需目标机验收确认迁移与注册数据 |
| 构建、自动测试、前端回归通过 | 通过 | 见第 2 节及本次 160 核心 / 61 Runner / 20 协议测试记录 |
| 区分“GPU 后端集成测试”与“真实 GPU 任务” | 已区分 | 第 2 节为自动化测试；第 4.1 节记录真实发现，第 4.4 节记录真实 1-step 训练 |

## 4. 目标机真实 GPU 验收

本机无 GPU。目标机（阶段二已验收）：`10.31.118.177`，用户 `hanw`，验收根 `/data/hanw/hw_code/gputrain-test`。

### 4.1 已执行：部署与真实设备发现（2026-10-06）

- SSH 使用专用密钥连接成功；测试目录中已有阶段二 Runner。部署前备份控制面 JAR、Runner JAR、Runner 配置及 MySQL `gputrain` 数据库。数据库备份：`phase3-backup/gputrain.pre-phase3.sql.gz`，SHA-256 `d68b2c8c4b07f7dfd059206d8739c5d129f58c39ebd7c15b5327b358de17f879`。
- 执行 `gputrain_phase3.sql` 后，核对已消费 enrollment 中 `hanw-phase2-runner` 唯一对应 owner 用户 ID `1`，才将旧 Runner 的 `owner_user_id` 绑定为 `1`。迁移后 5 张阶段三表存在。
- 控制面与 Runner 均运行，控制面健康检查 HTTP 200；Runner 配置权限为 `600`，环境白名单为 `LANG,CUDA_VISIBLE_DEVICES`。
- 目标机有 8 张 NVIDIA H20-3e；Driver / NVIDIA-SMI `570.211.01`，CUDA `12.8`，MIG 全部关闭。设备 UUID 已存入测试数据库，本文不重复列出。
- 阶段三首次部署没有收到快照。定位为 Runner 请求的 `gpu_instance_id` 不被该机 NVIDIA-SMI 接受（退出码 2）。改为查询驱动支持的 `gpu_uuid,pid,process_name,used_memory` 四列；MIG 状态仍从 GPU 查询读取，MIG 设备仍会被调度校验拒绝。补充测试后 Runner 全套 60 项测试通过。
- 修正版 Runner 上线后，数据库在最近 2 分钟内收到 8 张不同 GPU 的新鲜快照；最新快照时间 `2026-10-06 00:20:36 UTC`。最新的 8 张卡均显示显存已用且存在计算进程，MIG 启用数为 0。Runner 心跳正常。
- 本次没有创建阶段三任务或租约（数据库计数均为 0），也未启动、停止或影响其他用户的 GPU 进程。由于所有卡都被其他任务占用，单卡/多卡 fixture、任务停止与租约释放尚未执行。

本次实测只覆盖部署、控制面健康、Runner 心跳与真实 GPU 发现/占用上报；不代表 GPU 调度和训练任务验收通过。

### 4.2 前置确认（只查询，不做任何修改）

在 Runner 用户权限下执行并记录：

```
nvidia-smi --version
nvidia-smi --query-gpu=gpu_uuid,name,index,memory.total,mig.mode.current --format=csv,noheader
nvidia-smi --query-compute-apps=gpu_uuid,pid,process_name,used_memory --format=csv,noheader
```

记录：驱动 / `nvidia-smi` 版本、GPU 数量、GPU UUID（可部分脱敏）、MIG 是否启用、可见设备索引、Runner 用户可见的设备与进程。

### 4.3 真实 GPU 发现上报

- 部署阶段三 Runner（复用阶段二部署方式，HTTPS + HMAC）。
- 更新控制面前，先备份数据库并执行本版本 `backend/sql/gputrain_phase3.sql`，确认 `train_runner.owner_user_id` 已存在。阶段二遗留 Runner 的 owner 默认为 NULL，调度会拒绝使用；管理员须核验 enrollment/设备归属后再绑定，或为目标用户新注册 Runner。不要未经核验把旧 Runner 归给任意用户。
- 在 Runner 的 `runner.properties` 中将 `CUDA_VISIBLE_DEVICES` 加入 `environmentAllowlist`（例如 `LANG,CUDA_VISIBLE_DEVICES`），否则 Runner 会按本地策略拒绝调度命令。该变量由控制面根据租约中的 GPU UUID 注入，客户端不得自行指定或覆盖。
- 观察 Runner 周期上报（默认 15s）；确认控制面设备目录中 UUID、显存、MIG、快照新鲜度与目标机一致。
- 若 MIG 启用：确认设备被快速拒绝/隔离（阶段三不支持 MIG），不得把 MIG 实例当整卡。

### 4.4 真实训练短测（2026-10-06）

- 用户授权在共享 GPU 上运行 1–5 step 短测；本次固定为 **3 卡、1 step**，最低空闲显存 `70,000 MiB/卡`。调度读取新鲜 GPU 快照的 `total-used`，优先选择空闲显存较多的设备；保留唯一活跃租约，拒绝 MIG 和身份不确定设备，不操作外部进程。
- 固定 profile：脚本 `/model/hanw/ms-swift-imp3-research/fintune_val_shell/run_swift_docowl2_logidoc.sh`、配置 `configs/ocr_evidence/docowl2_srfund.env`、本地权重 `pretrained-imp/DocOwl2`、Conda Python `/home/hanw/.conda/envs/swift/bin/python`。Runner 只允许该 bash 脚本和配置参数、Runner 允许目录内工作目录，以及 profile 明确列出的环境变量；客户端不能提交可执行文件、参数或环境映射。
- 成功任务：`jobId=5`，Runner 1，最终状态 `SUCCEEDED`；训练日志记录 `global_step/max_steps=1/1`、`train_loss=3.98971343`、训练 step 约 `36.9s`。使用真实训练数据（训练集 3,982 行、验证集 1,316 行），生成 `checkpoint-1`。本次通过 `TRAIN_EVAL_STRATEGY=no` 关闭 step 内的完整验证集评估，`AUTO_EVAL=False` 关闭后置评估，因此短测不会启动 439 批验证。
- 输出目录：`/model/hanw/ms-swift-imp3-research/fintune_val_shell/work_dirs_docowl2/gputrain-smoke-20261006-0953_docowl2_srfund_lora/v0-20261006-095358/`。最终检查确认 `checkpoint-1` 存在，job 为 `SUCCEEDED`，本任务三张卡的 `hasActiveLease=false`，本次训练 Python 进程已退出；显存回到训练前采样水平。
- 取消路径也实际触发过：第一轮 1-step 训练完成后，脚本按原默认值进入 439 批验证；经 job STOP 接口终止了 Runner 管理的进程树，`nvidia-smi` 已无本任务进程且显存恢复。该轮因随后部署 Runner 时进程恢复状态无法证明，job `4` 进入 `QUARANTINED`，其三张卡租约继续保留（不自动释放）。最终成功任务使用了其它满足余量门槛的 GPU；被隔离租约应在 Runner/控制面完成受控重探测后再回收。
- 目标机时刻 GPU/NVIDIA-SMI 版本：H20-3e，NVIDIA-SMI `570.211.01`，CUDA `12.8`，MIG 关闭。日志中的 GPU UUID 不在本记录重复公开；任务 ID 和脱敏资源证据已保留。
- 本次验证不代表长时间、多 epoch 训练或完整验证集评估已完成；只证明真实数据上的单步训练、checkpoint 写出、Runner 终态同步与本任务租约释放。

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
