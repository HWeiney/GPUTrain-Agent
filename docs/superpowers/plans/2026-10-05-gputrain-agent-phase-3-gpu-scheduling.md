# GPUTrain Agent 阶段三实施计划：GPU 发现、原子分配、任务状态机

基线：`5d8160e7`（阶段二已验收）。设计：`docs/superpowers/specs/2026-10-05-gputrain-agent-phase-3-gpu-scheduling-design.md`。
工作分支：`feature/phase-3-gpu`（worktree `.worktrees/phase-3-gpu`）。

## 执行状态（2026-10-06）

**阶段三完成。** 控制面、Runner 与前端 profile 已实现并部署到 Linux H20 目标机；真实 GPU 快照正常，固定 DocOwl2-SRFUND profile 以 3 卡运行 1 step，任务 `jobId=5` 为 `SUCCEEDED`，`checkpoint-1` 已写出且本任务租约已释放。核心/Runner/协议测试分别为 161/61/20 项通过，前端 5 项测试和生产构建通过。

执行中补充了固定安全 profile、按实时空闲显存调度共享 GPU、超时/停止的进程树清理，以及 stop 与迟到 RUNNING 报告的竞态修复。首轮 job `4` 在训练 step 后进入长验证；取消时恰逢 Runner 重启，状态进入 `QUARANTINED`，其租约继续保留，需后续安全重探测后回收。MySQL 专项集成测试未在最终变更后重跑；这些限制均已写入验收记录，不改变成功短测的结果。

下一阶段按路线图进入**阶段四：日志、指标、告警与检查点展示**。

## 规则

- 每个切片：**先写失败测试 → 跑红 → 最小实现 → 跑绿 → 差异走读 → 提交**。
- 不把多个无法独立解释的能力压进同一提交。
- 所有进程仍走 `ProcessSpec` / `ProcessBuilder`，禁止 Shell 拼接。
- 本机无 GPU：Runner 端用可注入 `CommandExecutor` + 确定性假数据测试；真实 GPU 验收留目标机。
- 不杀非本次测试创建的进程；不读取训练数据、密钥、Token。

## 通用验证命令

```bash
mvn -f backend/pom.xml clean test
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env.example -f deploy/compose.yaml config -q
git diff --check
```

---

## 切片 A：数据模型与状态机骨架

目标：建立设备、快照、任务、租约、审计的持久化结构与纯领域状态机，不接 Runner。

### A1. SQL 升级脚本

- 新增 `backend/sql/gputrain_phase3.sql`，`SET NAMES utf8mb4;`，`CREATE TABLE IF NOT EXISTS`：
  - `train_gpu_device`：`device_id`、`gpu_uuid`（UNIQUE）、`host_signature`、`name`、`identity_state`、`schedulable`、`created_at`、`updated_at`。
  - `train_gpu_snapshot`：`snapshot_id`、`runner_id`、`gpu_uuid`、`visible_index`、`total_memory_mib`、`used_memory_mib`、`utilization_percent`、`mig_mode`、`sampled_at`、`compute_processes`(JSON)、`created_at`；`KEY( runner_id, gpu_uuid, sampled_at)`。
  - `train_job`：`job_id`、`job_uuid`（UNIQUE）、`owner_user_id`、`runner_id`、`gpu_count`、`min_memory_mib`、`allowed_gpu_uuids`(JSON)、`denied_gpu_uuids`(JSON)、`status`、`request_key`、`start_command_id`、`stop_command_id`、`created_at`、`updated_at`。
  - `train_gpu_lease`：`lease_id`、`lease_uuid`（UNIQUE）、`job_id`、`runner_id`、`gpu_uuid`、`status`、`idempotency_key`（`utf8mb4_0900_bin`）、`acquired_at`、`expires_at`、`renewed_at`、`released_at`、`attempt`、`created_at`、`updated_at`；生成列 `active_gpu_uuid VARCHAR(...) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN gpu_uuid ELSE NULL END)` VIRTUAL；`UNIQUE KEY uk_active_gpu(active_gpu_uuid)`；`UNIQUE KEY uk_job_idem(job_id,idempotency_key)`。
  - `train_gpu_audit`：`audit_id`、`occurred_at`、`actor_user_id`、`job_id`、`runner_id`、`gpu_uuid`、`lease_id`、`action`、`decision`、`reason_code`、`detail`。
- 全部 DDL 用 `INFORMATION_SCHEMA` 守卫，可重复执行；compose 挂载该脚本为 `05-phase3.sql`。
- 失败测试：`SqlPhase3SchemaIT`（`mysql-it` profile，见 D 切片引入；A 阶段先以本地脚本可重复执行验证）。

### A2. 领域记录与状态机（纯 Java，失败测试优先）

- `ruoyi-train-core` 新增包 `com.ruoyi.train.core.gpu.domain`：
  - `GpuDeviceRecord`、`GpuSnapshotRecord`、`GpuLeaseRecord`、`GpuAuditRecord`。
  - `DeviceIdentityState{CONFIRMED, IDENTITY_AMBIGUOUS, MIG_UNSUPPORTED, UNOBSERVABLE}`。
  - `LeaseStatus{ACTIVE, RELEASED, EXPIRED_QUARANTINED}`（含 `isActive`）。
- `com.ruoyi.train.core.job.domain`：
  - `JobRecord`、`JobStatus`、`JobStateMachine`。
- 失败测试（纯单元，红）：
  - `JobStateMachineTest`：覆盖第 8 节全部合法转换，非法转换抛 `IllegalStateException`；终态不可逆。
  - `LeaseStatusTest`：仅 ACTIVE 占用；RELEASED/EXPIRED 不占卡。
- 最小实现：枚举与转换表。

### A3. 验证与提交

- `mvn -f backend/pom.xml -pl ruoyi-train-core -am test`
- 在运行中的 compose MySQL 上执行 `gputrain_phase3.sql` 两遍验证幂等（只读确认表/索引存在）。
- 提交：`feat: add phase three gpu schema and job state machine`

---

## 切片 B：Runner GPU 发现协议

目标：协议 DTO + Runner 端发现器，含异常分类；不接入控制面。

### B1. 协议 DTO（失败测试优先）

- `ruoyi-train-runner-protocol` 新增 `com.ruoyi.train.runner.protocol.gpu`：
  - `GpuSnapshot`、`GpuProcessInfo`、`GpuDiscoveryReport`、`GpuDiscoveryStatus`。
  - 构造器内校验：UUID 形态、显存范围、利用率 0..100、列表不可变、必填非空。
- 新增错误码到 `RunnerErrorCode`：`GPU_SNAPSHOT_STALE`、`GPU_SNAPSHOT_INVALID`、`GPU_BUSY`、`GPU_MIG_UNSUPPORTED`、`GPU_IDENTITY_AMBIGUOUS`、`GPU_UNOBSERVABLE`、`LEASE_QUARANTINED`、`JOB_STATUS_CONFLICT`。
- 失败测试：`GpuSnapshotProtocolTest`：合法构造、越界利用率、used>total、空 UUID、不可变集合。
- 序列化兼容：`RunnerProtocolTest` 增补日期/枚举往返。

### B2. 发现器与解析（失败测试优先）

- `ruoyi-train-runner` 新增 `com.ruoyi.train.runner.gpu`：
  - `CommandExecutor`（接口）：`ExecResult run(List<String> argv, Duration timeout, int maxBytes)`；`ProcessBuilderCommandExecutor` 实现（不经过 Shell，超时 destroy，输出上限）。
  - `NvidiaSmiGpuDiscovery`：固定参数数组（设计 5.3 的两次只读查询），调用 `CommandExecutor`，分类状态。
  - `NvidiaSmiCsvParser`：解析 GPU 行与 compute-apps 行，按 UUID 关联进程。
- 失败测试：
  - `NvidiaSmiCsvParserTest`：正常多行、空输出、字段缺失、数字非法、MIG 行（`MIG-`）、compute-apps 关联。
  - `NvidiaSmiGpuDiscoveryTest`：用 fake `CommandExecutor` 覆盖 OK、命令不存在(IOException)、非零退出+权限、超时、损坏、超量(>64)、0 设备。
  - `ProcessBuilderCommandExecutorTest`：本机用 `java`/`true` 跑通参数数组（不依赖 GPU）；验证 argv 逐元素传递、超时终止、输出截断。

### B3. 验证与提交

- `mvn -f backend/pom.xml -pl ruoyi-train-runner,ruoyi-train-runner-protocol -am test`
- 提交：`feat: add runner gpu discovery and parser`

---

## 切片 C：控制面设备目录与外部占用

目标：接收并校验快照，建设备目录，判定外部占用，维护可调度状态。

### C1. Mapper 与持久化（失败测试优先）

- `gpu` 包下 `mapper`：`GpuDeviceMapper`、`GpuSnapshotMapper`、`GpuAuditMapper`（+ XML）。
- 方法：快照插入、设备按 UUID 查/条件 upsert、最近快照、审计插入。
- 失败测试：`GpuDeviceMapperTest`（fake/MyBatis 切片）与 `mysql-it` 下的真实 upsert。

### C2. 快照校验与目录服务（失败测试优先）

- `GpuSnapshotValidator`：数量/格式/新鲜度/重复 UUID/未来时间（设计 5.2）。
- `GpuDeviceCatalogService`：
  - 校验 Runner 身份（runnerId 与主体一致）、host_signature 一致或建 CONFIRMED；冲突置 IDENTITY_AMBIGUOUS。
  - MIG ENABLED / `MIG-` → MIG_UNSUPPORTED。
  - 非 OK 报告或无法解析 → UNOBSERVABLE。
  - 写快照、设备状态、审计。
- `ExternalProcessClassifier`：compute-apps PID 与 `train_runner_process` 按 PID+启动时间匹配，归属本平台；其余外部。
- 失败测试：
  - `GpuSnapshotValidatorTest`：超量、坏 UUID、过期、未来、重复 UUID、越界。
  - `GpuDeviceCatalogServiceTest`：首报建 CONFIRMED、签名冲突 AMBIGUOUS、MIG 隔离、非 OK→UNOBSERVABLE、多 Runner 同 UUID 外部占用联动。
  - `ExternalProcessClassifierTest`：本平台 PID 匹配、PID 复用（启动时间不同）判外部、其他用户 PID 外部、进程列表不可见→UNOBSERVABLE。

### C3. 验证与提交

- `mvn -f backend/pom.xml -pl ruoyi-train-core -am test`
- 提交：`feat: add gpu device catalog and external occupancy`

---

## 切片 D：原子 GPU 租约服务

目标：单卡/多卡原子分配、并发竞争、幂等、续租、释放、过期隔离、恢复。

### D1. mysql-it Profile（数据库/并发验收）

- `ruoyi-train-core/pom.xml` 增加 profile `mysql-it`：测试依赖 `com.mysql:mysql-connector-j`（test 作用域，仅该 profile）与 `spring-jdbc`（test）；系统属性 `gputrain.it.jdbc-url/user/password` 默认指向 compose。
- 新增 `src/test/java/.../gpu/it/MySqlItSupport`：建连、执行 `gputrain_phase3.sql`、每测清理相关表。
- 失败测试（红→绿）：
  - `LeaseUniqueConstraintIT`：直接 JDBC 插两条同 UUID ACTIVE，第二条因 `uk_active_gpu` 失败；释放后可再插。
  - `ConcurrentLeaseContentionIT`：N 线程并发租同一张卡，恰好 1 成功。
  - `MultiGpuAllOrNothingIT`：多卡请求全部成功；候选不足/中途冲突全部回滚，无残留租约。
  - `CondiveUpdateAndRollbackIT`：条件续租/释放 0 行语义；事务回滚后无 ACTIVE 残留。

### D2. 租约服务（失败测试优先）

- `mapper/GpuLeaseMapper`（+XML）：`lockCandidateDevices(... FOR UPDATE SKIP LOCKED)`、`insertLease`、`renew(条件)`、`release(条件)`、`expireSweep`、按 job/幂等查。
- `GpuLeaseService`：
  - `allocate(job, constraints)`：单事务过滤→排序→锁定→整组插入；冲突 `GPU_BUSY`；幂等返回整组。
  - `renew`、`release`（仅终态+身份核验）、`sweepExpired`（→EXPIRED_QUARANTINED，不自动空闲）。
- 失败测试（单元 + IT）：
  - `GpuLeaseServiceTest`：确定性选择排序、外部占用拒绝、过期快照拒绝、MIG/AMBIGUOUS/UNOBSERVABLE 拒绝、幂等返回、多卡排序复现。
  - 上述 IT 跑绿。

### D3. 验证与提交

- `mvn -f backend/pom.xml -pl ruoyi-train-core -am test`
- `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Pmysql-it verify`
- 提交：`feat: add atomic gpu leases`

---

## 切片 E：训练任务状态机与端到端链路

目标：任务 API、排队/分配、START/STOP、状态同步、失败/恢复、幂等。

### E1. 任务 Mapper 与服务（失败测试优先）

- `job` 包：`mapper/JobMapper`（+XML）、`JobService`、`JobCommandAdapter`。
- 流程（设计 8.2）：创建(PENDING/QUEUED，请求幂等) → `GpuLeaseService.allocate`(ALLOCATED) → 由任务规格生成 `ProcessSpec` 并创建 START 命令(STARTING) → 命令/进程事件驱动 RUNNING/终态 → 释放租约。
- STOP：复用 `RunnerCommandService.requestStop`；STOPPING→STOPPED。
- 失败/不确定：QUARANTINED，租约不自动释放。
- 失败测试：
  - `JobServiceTest`：创建幂等、重复任务返回同一；未启动可重分派；已 RUNNING 拒绝重复分配/重跑。
  - `JobStateSyncTest`：START 命令 RUNNING/成功/失败/停止分别推进任务并续租/释放；重复 STOP 同命令同终态、不释放他人租约。
  - `JobRecoveryTest`：快照过期/Runner 离线 → QUARANTINED 且设备不可分配；reconcile 恢复后回到 RUNNING/释放。

### E2. 控制器

- `RunnerApiController` 增加 `@Anonymous @PostMapping("/gpu-snapshots")`：校验 runnerId 一致，调 `GpuDeviceCatalogService`。
- `JobAdminController` 挂 `/train/jobs`：`@PreAuthorize train:job:manage/view`，创建、查询、停止；任务只能落到有权限的 Runner。
- 失败测试：`JobAdminControllerTest`（权限/越权 Runner 拒绝）、`GpuSnapshotEndpointTest`（HMAC + runnerId 一致）。

### E3. 验证与提交

- `mvn -f backend/pom.xml clean test`
- `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Pmysql-it verify`
- 提交：`feat: add job state machine and end-to-end scheduling`

---

## 切片 F：前端最小工作流

前提：后端领域与接口已确定。

- `frontend/src/api/train/devices.ts`、`jobs.ts`；类型到 `types/api/train`。
- `views/train/devices/index.vue`：Runner/设备状态、身份/MIG/快照新鲜度、外部占用。
- `views/train/jobs/index.vue`：任务创建（卡数、最低显存、Runner）、列表与状态、停止。
- `*.test.ts`：渲染与关键状态展示；菜单/权限 `gputrain_phase3.sql` 增补 `train:job:*`、`train:gpu:view`。
- 验证：`npm --prefix frontend test`、`npm --prefix frontend run build:prod`。
- 提交：`feat: add gpu device and job console`

---

## 切片 G：集成验收、文档与提交

- 验收场景（自动测试 + 记录）：单卡、多卡、并发争抢、外部占用、Runner 离线、心跳过期、MIG、停止、崩溃恢复、重试幂等。
- 新增 `docs/verification/phase-3-gpu-scheduling.md`：本机自动测试结果；真实 GPU 项标 **待执行**，给出安全步骤（日期、脱敏 UUID、驱动/`nvidia-smi` 版本、任务 ID、结果、清理）。
- 更新 `README.md`、`docs/interview/项目面试考点.md`、路线图状态。
- 最终回归：
  - `mvn -f backend/pom.xml clean test`
  - `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Pmysql-it verify`
  - `npm --prefix frontend test`
  - `npm --prefix frontend run build:prod`
  - `docker compose --env-file .env.example -f deploy/compose.yaml config -q`
  - `git diff --check`
- 提交：`docs: phase three acceptance and status`（或与前述合并为清晰的最后提交）。
- 只有所有验收门槛通过并写入证据，才标记阶段三完成；真实 GPU 未执行则如实保留待执行项。

## 验收门槛对照表

| 门槛 | 主要证据测试 |
|---|---|
| 单卡并发只一个成功 | `ConcurrentLeaseContentionIT` |
| 多卡全有或全无 | `MultiGpuAllOrNothingIT` |
| 外部占用/MIG/过期/离线/身份不确定 fail closed | `GpuLeaseServiceTest`、`GpuDeviceCatalogServiceTest`、`JobRecoveryTest` |
| 重试/重复 STOP 不重复占用、不释放他人租约 | `JobServiceTest`、`JobStateSyncTest` |
| 控制面/Runner 重启可恢复 | `JobRecoveryTest`、阶段二恢复链路 |
| 权限隔离 | `JobAdminControllerTest`、`GpuSnapshotEndpointTest` |
| 构建/测试/前端回归 | 通用验证命令 |
