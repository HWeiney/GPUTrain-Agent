# GPUTrain Agent 阶段三设计：GPU 资源发现、原子分配与任务状态机

## 1. 目标与验收边界

阶段三在阶段二“用户级 Runner + 结构化命令”的基础上，建立从 GPU 发现到任务租用、训练进程执行与释放的可信闭环。

退出条件（必须有证据，不允许口头声称）：

- Runner 发现用户可见的 NVIDIA GPU，上报经过校验的 GPU 快照；`nvidia-smi` 缺失、超时、输出损坏、权限被拒时明确标记不可调度。
- 控制面区分三类事实：Runner 上报的可观察状态、控制面创建的有效租约、外部进程占用。
- 提交训练任务时，控制面原子分配满足约束的 GPU；并发请求不得重复分配同一张卡。
- 任务、命令、GPU 租约与 Runner 进程之间有稳定关联与幂等语义。
- 开始、成功、失败、停止、异常退出时，任务状态与租约状态可恢复、一致、可审计。
- Runner 离线、心跳过期、进程身份不一致、控制面重启或数据库事务中断时，仍被占用的 GPU 不会被错误分配给另一任务。

阶段三**不**实现：日志 SSE、指标与告警、Qwen Agent、MCP。阶段三只调度 fixture/训练进程，不提供任意 Shell 能力。

本地 macOS 无 NVIDIA GPU，不做真实 GPU 验收；真实 GPU 验收作为目标机（`10.31.118.177` / `hanw`）的明确步骤，见 `docs/verification/phase-3-gpu-scheduling.md`。

## 2. 复用阶段二的既有事实（不另起架构）

以下结论来自实际代码（基线提交 `5d8160e7`）：

- 构建：Java 17、Spring Boot 3、Maven Reactor（`backend/pom.xml`），RuoYi 版本 3.9.2。
- 协议模块 `ruoyi-train-runner-protocol` 为纯 Java record：
  - `ProcessSpec(workingDirectory, executable, arguments, environment, timeoutSeconds, idempotencyKey)`
  - `RunnerCommandLease(commandId, type, process, targetCommandId, leaseUntil, attempt)`
  - `RunnerCommandReport(previousStatus, status, pid, processStartedAt, exitCode, errorCode, errorMessage, attempt)`
  - `RunnerProcessSnapshot(commandId, pid, processStartedAt, executable, argumentsSha256, status, exitCode, errorCode, attempt)`
  - `RunnerCommandType{START_PROCESS, STOP_PROCESS}`、`RunnerCommandStatus`、`RunnerProcessStatus`、`RunnerErrorCode`
- 控制面 `ruoyi-train-core`：
  - `RunnerApiController` 挂 `/runner-api/v1`：`heartbeat`、`commands/poll`、`commands/{id}/reports`、`processes/reconcile`，均为 HMAC 认证（`@Anonymous` + `RunnerAuthenticationInterceptor`）。
  - `RunnerAdminController` 挂 `/train/runners`，使用若依登录态与 `@PreAuthorize`。
  - 服务：`RunnerCommandService`（命令创建/领取/上报/停止）、`RunnerHeartbeatService`、`RunnerReconciliationService`、`RunnerEnrollmentService`。
  - 命令状态机：`QUEUED -> LEASED -> RUNNING -> SUCCEEDED|FAILED|STOPPED`，另有 `REJECTED`。
  - 关键保证：**已上报 RUNNING 的命令不会因命令租约过期而自动重跑**，必须通过 reconcile 确定状态。
- Runner 端 `ruoyi-train-runner`：
  - `RunnerApplication` 单命令循环 + 独立心跳；`ProcessPolicy`（目录/可执行/环境校验）、`ProcessSupervisor`（启动/观察/停止/恢复）、`ProcessStateStore`（`.gputrain/state/` 原子状态）。
  - `RunnerControlClient` 用 `java.net.http.HttpClient`，参数全部走结构化 JSON，不经过 Shell。
- 持久化：MySQL 8.4（`train_runner`、`train_runner_enrollment`、`train_runner_command`、`train_runner_process`），MyBatis XML mapper 位于 `ruoyi-train-core/src/main/resources/mapper/train/`；Redis 仅存 Nonce 与短期在线标记。

阶段三的所有新能力都接入上述命令、上报与 reconcile 通道，不新增第二套进程执行通道。

## 3. 模块与包布局

| 新增/修改位置 | 职责 |
|---|---|
| `ruoyi-train-runner-protocol`：`...protocol.gpu` 包 | GPU 快照、GPU 进程、发现结果 DTO；新增 GPU 相关错误码 |
| `ruoyi-train-runner`：`...runner.gpu` 包 | `GpuDiscovery` 接口、`NvidiaSmiGpuDiscovery`、CSV 解析器、`CommandExecutor` 抽象 |
| `ruoyi-train-runner`：`RunnerApplication` | 按发现周期采集快照并上报（复用签名客户端） |
| `ruoyi-train-core`：`...core.gpu` 包 | 设备目录、快照校验、外部占用判定、租约服务、过期/隔离 sweep |
| `ruoyi-train-core`：`...core.job` 包 | 训练任务聚合、任务状态机、任务服务、任务控制器 |
| `ruoyi-train-core`：`RunnerApiController` | 新增 `POST /runner-api/v1/gpu-snapshots` |
| `backend/sql/gputrain_phase3.sql` | 设备、快照、任务、租约、审计表及索引；幂等可重复执行 |
| `frontend/src/views/train/devices`、`.../jobs` | 设备/Runner 状态查看、任务创建与状态展示 |

## 4. GPU 身份与 MIG 策略

### 4.1 持久身份

- 使用 NVIDIA GPU UUID（`--query-gpu=gpu_uuid`，形如 `GPU-xxxxxxxx-...`）作为持久身份；可见设备序号 `index`（ordinal）仅作展示与本次快照内定位，**绝不**作为租约或持久键。
- 设备目录中 `train_gpu_device` 以 `gpu_uuid` 为唯一键（canonical 物理设备），并保存 `host_signature`（主机名 + 驱动版本 + `nvidia-smi` 版本的组合哈希）。
- 快照事实表 `train_gpu_snapshot` 以 `(runner_id, gpu_uuid, sampled_at)` 记录每次观察，是可观察事实，不直接等同于“可分配”。

### 4.2 跨 Runner / 命名空间映射

部署形态为一台裸金属多用户主机，同一物理 GPU 可被多个 Linux 用户的 Runner 同时看到。规则：

- 快照到达时，若该 `gpu_uuid` 尚无 canonical 设备，则按其 `host_signature` 建设备。
- 若同一 `gpu_uuid` 已存在但 `host_signature` 不同（容器重映射、跨主机等无法确认物理唯一性的情形），设备置为 `IDENTITY_AMBIGUOUS`，**fail closed**，不允许任何租约。
- 同一 `gpu_uuid` 被多个 Runner 看到时，任一 Runner 的快照中出现外部进程，都会使该设备对所有 Runner 不可分配（保守处理，见第 6 节）。

### 4.3 MIG：阶段三不支持，快速隔离

- 快照含 `mig_mode.current`（`N/A`/`ENABLED`/`DISABLED`）。
- 任何设备 `mig_mode = ENABLED`，或出现 `MIG-` 前缀的 UUID / 计算实例，设备（必要时整主机）置为 `MIG_UNSUPPORTED`，不参与分配。
- 不允许把 MIG 实例误判为整卡；不做 MIG 切分，不做 MIG 实例级租约。

## 5. GPU 发现快照协议

### 5.1 快照字段

`GpuSnapshot`（单卡）：

```text
uuid                 // GPU UUID，必填，正则校验
name                 // 产品名
visibleIndex         // 本次枚举内的 ordinal，仅展示
totalMemoryMiB       // >= 0
usedMemoryMiB        // >= 0，且 <= total
utilizationPercent   // 0..100
migMode              // ENABLED / DISABLED / NA
sampledAt            // Instant
computeProcesses[]   // GpuProcessInfo
```

`GpuProcessInfo`：`pid`、`processName`、`usedMemoryMiB`、`gpuInstanceId`（可为空）。

`GpuDiscoveryReport`：`runnerId`、`hostName`、`driverVersion`、`nvidiaSmiVersion`、`hostSignature`、`discoveredAt`、`status`（`OK` / `UNAVAILABLE` / `PERMISSION_DENIED` / `TIMEOUT` / `CORRUPT_OUTPUT` / `NO_DEVICES`）、`gpus[]`、`errorMessage`（固定安全文本，不回显原始命令输出）。

### 5.2 输入校验（控制面）

- 数量：`0 <= gpus.size <= 64`；超限拒绝整份报告。
- 格式：UUID 必须匹配 NVIDIA UUID 形态；显存非负且 `used <= total`；利用率 0..100；必填字段非空。
- 时间新鲜度：`sampledAt` 与控制面接收时间偏差不超过 `staleAfter`（默认 30 秒），且不允许明显未来时间（时钟偏差上限，默认 60 秒）。
- 同一报告内 UUID 不得重复。
- 报告整体受 `gputrain.runner-security.max-request-body-bytes` 同类的 body 上限约束（独立配置，默认 262144 字节）。
- 任一校验失败：该 Runner 的全部设备不更新为“可分配”，失败写入审计。

### 5.3 nvidia-smi 执行适配器（Runner 端）

- 使用固定参数数组、`ProcessBuilder`，**不经过 Shell**，两次只读查询：
  1. `nvidia-smi --query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current --format=csv,noheader,nounits`
  2. `nvidia-smi --query-compute-apps=gpu_uuid,pid,process_name,used_memory,gpu_instance_id --format=csv,noheader,nounits`
- 命令路径通过配置给出（默认 `nvidia-smi`，可显式绝对路径）；`CommandExecutor` 带超时（默认 10 秒）与输出大小上限（默认 1 MiB）。
- 异常分类：
  - 可执行文件不存在 / `IOException` → `UNAVAILABLE`
  - 非零退出且 stderr 含权限语义 → `PERMISSION_DENIED`
  - 超时 → `TIMEOUT`
  - 输出行数/字段不匹配、数字无法解析 → `CORRUPT_OUTPUT`
  - 成功但解析出 0 张设备 → `NO_DEVICES`
- 以上任何非 `OK` 状态：该 Runner 不产生“空闲”结论，控制面将其设备标记为不可调度（`UNOBSERVABLE`），**绝不回退为空闲**。

## 6. 外部进程与“空闲”判定

### 6.1 三类占用的区分

- 可观察状态：最新快照中的显存、利用率、compute-apps。
- 有效租约：控制面持有的 `ACTIVE` 租约。
- 外部进程：compute-apps 中无法证明“属于本平台”的 PID。

### 6.2 进程归属判定

compute-apps 中的 PID 只有满足下列条件才认定为“本平台进程”：

- 该 Runner 的 `train_runner_process` 中存在匹配行，且 PID 与 `process_started_at`（启动时间）都一致（防 PID 复用）；
- 对应命令为 `START_PROCESS` 且属于当前任务；
- 进程到 GPU UUID 的映射来自同一次 `nvidia-smi` 的 `gpu_uuid` 关联。

任何其他 PID（其他用户、其他容器、残留进程）都算外部占用。

### 6.3 空闲判据（全部满足才可分配）

1. 存在该 Runner 的 `OK` 报告，且最新快照在 `staleAfter` 内（默认 30 秒）。
2. 设备 `mig_mode = DISABLED/NA`，身份状态为 `CONFIRMED`。
3. 该 GPU UUID 无任何 `ACTIVE` 租约。
4. compute-apps 中不存在外部 PID；本平台进程仅限“本任务”——分配前目标设备上不得有任何其他平台任务。
5. 进程列表可见、可解析；若因权限无法看到进程列表（compute-apps 被隐藏），即使显存看似空闲也判定 `UNOBSERVABLE`，拒绝分配。

宁可拒绝，不得猜测。`used_memory` 与 compute-apps 明显不一致（有显存但无可见进程且无法解释）时，同样 `UNOBSERVABLE`。

## 7. 原子 GPU 租约

### 7.1 租约表与唯一性约束

`train_gpu_lease`：

```text
lease_id, lease_uuid, job_id, runner_id, gpu_uuid,
status             // ACTIVE / RELEASED / EXPIRED_QUARANTINED
idempotency_key    // 与 job 绑定，utf8mb4_0900_bin
acquired_at, expires_at, renewed_at, released_at,
attempt, created_at, updated_at
```

同卡并发安全的最终仲裁交给数据库：

- 增加生成列 `active_gpu_uuid = CASE WHEN status='ACTIVE' THEN gpu_uuid ELSE NULL END`，
  并建唯一键 `uk_active_gpu (active_gpu_uuid)`。MySQL 唯一索引允许多个 NULL，因此同一时刻同一 `gpu_uuid` 至多一条 `ACTIVE` 租约，释放/过期后该列为 NULL，可再次分配。
- 不依赖 JVM `synchronized` 或单实例锁作为正确性来源（仅用于减少无谓冲突）。

### 7.2 单卡 / 多卡分配事务

- 在一个事务内（`READ_COMMITTED`），用确定性条件选出候选设备并 `SELECT ... FOR UPDATE SKIP LOCKED` 锁定设备行：
  - 过滤：Runner、新鲜快照、`CONFIRMED`、非 MIG、无 ACTIVE 租约、无外部占用、`total_memory >= minMemoryMiB`、允许/禁止列表。
  - 排序：`host_signature, gpu_uuid`（稳定、可复现、可解释）。
- 多卡：所需 `gpuCount` 张设备在同一事务内一次性锁定并一次性插入全部租约；任一失败或候选不足，**整体回滚**，不遗留部分租约。
- 插入命中 `uk_active_gpu` 冲突（并发争抢）→ 回滚该事务，调用方收到确定性冲突结果（`GPU_BUSY`），可重试整个请求。
- 结果：并发争抢同一张卡只有一个事务成功。

### 7.3 幂等

- 任务级分配以 `(job_id, idempotency_key)` 唯一；重复请求返回已存在的整组租约，不新建设备占用。
- 仅当租约处于可再分配状态（任务尚未真正启动）时才允许重新执行分配逻辑；已进入 STARTING/RUNNING 的任务拒绝重复分配。

### 7.4 续租、释放、过期、恢复

- 续租：进程 RUNNING 期间，随命令上报/reconcile 对 `ACTIVE` 租约做条件 `UPDATE ... SET expires_at=?, renewed_at=? WHERE status='ACTIVE'`；`expires_at` 滑动窗口（默认 90 秒）。
- 释放：只有当对应命令达到终态且进程身份核验通过（PID/启动时间/退出码），才把租约置 `RELEASED`（`active_gpu_uuid` 变 NULL）。
- 过期：sweep 把 `expires_at < now` 的 `ACTIVE` 租约置为 `EXPIRED_QUARANTINED`。**过期不等于空闲**：必须重新探测并核对关联进程；核对不确定则设备进入 `QUARANTINED`，等待人工/自动再核对。
- Runner 离线：在线标记过期后，其 ACTIVE 租约不自动释放；关联任务进入 QUARANTINED，设备保持不可分配，直到 Runner 恢复并完成 reconcile。
- 控制面重启 / 事务中断：状态全部来自 MySQL；启动时以持久化状态为准恢复，未提交事务由数据库回滚，不会出现“已占卡但无租约记录”或“已释放但仍被锁”。

## 8. 训练任务状态机

### 8.1 状态与合法转换

```text
PENDING -> QUEUED -> ALLOCATING -> ALLOCATED -> STARTING -> RUNNING -> SUCCEEDED
                                          |          |          |
                                          v          v          v
                                       FAILED     FAILED      FAILED
                                                             STOPPING -> STOPPED
任意非终态 -> QUARANTINED   (身份/进程/快照不确定；不可分配，等待核对)
PENDING/QUEUED -> CANCELED（尚未分配，可取消）
```

- 终态：`SUCCEEDED`、`FAILED`、`STOPPED`、`CANCELED`。
- `QUARANTINED` 是安全暂停态：设备不可再分配；经重新探测与身份核对通过后可回到 RUNNING/释放，核对失败则转 FAILED 并保留审计。
- 转换由 `JobStateMachine` 集中校验，非法转换抛确定性冲突错误。

### 8.2 事件来源与链路

任务不直接接收 Runner 进程事件，而是复用阶段二命令链路：

```text
创建任务(PENDING/QUEUED)
 -> 原子分配租约(ALLOCATING/ALLOCATED)
 -> 创建 START_PROCESS 命令(STARTING)，ProcessSpec 由任务规格生成
 -> Runner 领取并启动；命令 RUNNING -> 任务 RUNNING（同时续租）
 -> 命令 SUCCEEDED/FAILED/STOPPED -> 任务对应终态，并释放租约
 -> STOP：复用 requestStop 的 STOP_PROCESS 命令 -> STOPPING -> STOPPED
```

- 事件来源仅限：命令 `reports` 与 `processes/reconcile`（HMAC 认证的 Runner），以及管理 API 的创建/取消/停止操作。
- 每条命令、租约都带 `job_id`，形成 任务 ↔ 命令 ↔ 进程 ↔ 租约 的稳定关联。

### 8.3 幂等与不重跑

- 创建任务带请求幂等键；重复创建返回同一任务。
- 每个任务只创建一次 START 命令；命令/租约唯一键防止重复进程。
- 任务重试不重新执行已 RUNNING 的命令，也不重复占用 GPU；仅在可证明未启动（命令未 RUNNING 且租约未生效）时才允许重新分派。
- 重复 STOP 返回同一 STOP 命令与同一终态，不会释放其他任务的租约。
- 与阶段二一致：RUNNING 命令不因租约/命令租约过期而自动重跑；冲突时进入 QUARANTINED 而非重启进程。

## 9. 多卡请求约束

- 请求字段：`gpuCount`（>=1）、`minMemoryMiBPerGpu`、`allowedGpuUuids`（可空，白名单）、`deniedGpuUuids`（可空，黑名单）、`sameHost`（默认且必须 true）、`runnerId`（可指定或由调度选定）。
- 阶段三限定单主机、单 Runner：多卡任务的所有 GPU 必须在同一 Runner 可见集合内。
- 选择算法确定性：过滤 → 按 `(host_signature, gpu_uuid)` 排序 → 取前 `gpuCount`；结果可复现、可解释。
- 全有或全无：候选不足或任一锁定失败，整组不分配。

## 10. 权限与审计

- 任务与设备管理走若依登录态 + 新权限：`train:job:manage`、`train:job:view`、`train:gpu:view`；沿用 `@PreAuthorize` 与数据范围。
- Runner 接口仅以 HMAC 确认 Runner 身份，快照中的 `runnerId` 必须与签名主体一致；Runner 不能通过传入任意 userId 或任意 GPU UUID 获得授权。
- 任务只能调度到：任务创建者有权使用的 Runner（Runner 归属或数据范围）、有权访问的数据目录（阶段二 `allowed_root`）以及该 Runner 可见的设备。
- 审计表 `train_gpu_audit`：`time`、`actor_user_id`、`job_id`、`runner_id`、`gpu_uuid`、`lease_id`、`action`、`decision`、`reason_code`、`detail`（不含密钥、Token、环境变量、命令明文秘密）。
- 关键动作（快照接收/拒绝、分配成功/失败、续租、释放、过期隔离、Runner 离线、状态转换冲突）均落审计。

## 11. 数据库原子性机制汇总

- 唯一约束：`uk_active_gpu(active_gpu_uuid)` 保证同卡至多一个 ACTIVE 租约。
- 行锁：候选设备 `SELECT ... FOR UPDATE SKIP LOCKED`，同事务内完成锁定与租约插入。
- 条件更新：续租、释放、命令推进都带 `WHERE status/attempt/...` 预期值，失败即 0 行，按冲突处理。
- 事务边界：分配（含多卡）在单事务内完成；失败回滚不留残留。
- 隔离级别：`READ_COMMITTED`，冲突后可读取已提交获胜记录以返回幂等结果。
- 生成列与索引在 `gputrain_phase3.sql` 中幂等创建（`INFORMATION_SCHEMA` 守卫，可重复执行）。

## 12. 测试策略

- 单元测试（纯领域）：
  - GPU 身份/MIG 判定、快照校验、外部占用归属、空闲判据、租约选择排序、任务状态机转换、多卡约束与幂等键。
  - nvidia-smi CSV 解析：正常、空输出、坏输出、字段缺失、数字非法、超量设备、MIG 行。
  - 发现器：命令不存在、非零退出、权限拒绝、超时、输出超限、0 设备。
- 集成测试（MySQL，真实约束）：
  - 新增 `mysql-it` Maven Profile，指向 compose MySQL（`127.0.0.1:3307`），通过 JDBC 验证唯一约束、条件更新、事务回滚、并发租约竞争（多线程）、多卡全有或全无。
  - 提供可重复命令，例如 `mvn -f backend/pom.xml -pl ruoyi-train-core -Pmysql-it verify`。
- 端到端（控制面，无真实 GPU）：用确定性假快照/假发现器走“创建→分配→START→RUNNING→成功/失败/停止”，验证任务与租约状态、重复 STOP、过期快照与 Runner 离线拒绝调度。
- 回归：`mvn -f backend/pom.xml clean test`、前端 Vitest 与 `build:prod`、compose config、`git diff --check`。
- 真实 GPU 验收：仅在目标机执行短时 fixture，记录日期、脱敏 UUID、驱动/`nvidia-smi` 版本、任务 ID、结果与清理证据；不以模拟数据冒充实机结果。

## 13. 取舍与明确不做

- 不支持 MIG：遇到即隔离，避免误判整卡。
- 不做跨主机 / 跨 Runner 多卡：阶段三限定单主机单 Runner。
- 过期租约不自动释放：强制重新探测与身份核对，防止把仍在跑的卡再分配。
- 进程不可见或映射不可信即拒绝：牺牲调度成功率换取安全。
- 不引入日志、指标、Agent、MCP，避免阶段耦合。
- 不新增 Testcontainers 重依赖：复用 compose MySQL，以 `mysql-it` profile 提供可重复的数据库/并发验收。
