# GPUTrain SSH Job Execution and Runner Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将训练提交、GPU 租约、日志监控和停止迁移到多服务器 SSH 路径，完成真实断线恢复验证后删除 Runner 运行时，保留旧任务可读。

**Architecture:** 前置计划 `2026-10-07-gputrain-ssh-server-inventory.md` 已提供服务器/账户、加密 SSH 会话和新鲜 GPU 状态。新任务在数据库中绑定 server/account；数据库事务只做任务与 GPU 租约，提交后由独立调度器通过用户级 `systemd` 临时单元启动。对账器以确定性单元名查询远端事实，驱动状态、日志和租约；旧 Runner 记录保留只读，直到 SSH 实测及回滚门槛通过才删运行时代码。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis/MySQL、Apache MINA SSHD `2.20.0`（包含 SFTP）、Linux `systemd --user`、Vue 3/TypeScript、Vitest、JUnit 5。

## Global Constraints

- 新任务只使用 SSH 目标；每次指定 `serverId`、当前用户的 `accountId` 和同一服务器的恰好 3 个 GPU UUID，固定 `docowl2-srfund` 和 1–5 步。
- 目标 Linux 用户的 `systemd --user` 必须能在 SSH 会话结束后持续运行临时单元，且支持计划使用的 stdout/stderr 日志属性；预检失败只允许看状态，不允许提交训练。
- `requestKey` 和远程单元名均确定性且可重复查询；任何 SSH 启动超时先对账，不直接发第二次启动。
- `UNKNOWN/RECONCILING/QUARANTINED` 不释放 GPU 租约；仅核对远程进程退出且任务终结后释放。停止只作用于本任务单元，不按名称模糊杀进程。
- GPU 唯一租约键为 `(server_id,gpu_uuid)`，同一物理服务器不同账户不能双重租约；跨服务器 UUID 相同可以各自租用。
- 旧 Runner 历史任务、日志、指标和审计必须可读；删除 Runner 模块前，所有运行中的旧任务终结并核对租约，无破坏性 DROP。
- 训练和停止只在获授权的测试 GPU 上实测，不终止外部进程；本机无 GPU 不能充当服务器端训练验收。

## 文件与跨任务契约

| 职责 | 文件 |
|---|---|
| 新任务/租约迁移 | `backend/sql/gputrain_phase9_ssh_jobs.sql`、`deploy/compose.yaml`、`backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/job/{domain,mapper,service,web}` |
| SSH 执行 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/job/{SshJobDispatcher,SshJobReconciler,SystemdCommandBuilder,SystemdUnitState}.java` |
| 资源与日志 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/gpu/SshGpuLeaseService.java`、`server/job/SshLogCollector.java`、相关 Mapper/XML |
| 前端 | `frontend/src/api/train/{jobs,servers,devices}.ts`、`frontend/src/views/train/{jobs,devices,overview}/index.vue`、任务纯逻辑测试 |
| Runner 退役 | `backend/pom.xml`、`backend/ruoyi-train-core/pom.xml`、`backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/**`、`backend/ruoyi-train-runner/**`、`backend/ruoyi-train-runner-protocol/**`、`deploy/runner/**`、README/部署文档 |

新任务 `CreateJobRequest(profileId,maxSteps,requestKey,serverId,accountId,allowedGpuUuids)`；持久化 `executionMode=SSH,serverId,accountId,remoteUnitName,remoteWorkingDirectory`。旧行 `executionMode=RUNNER_LEGACY`（迁移填充），`runnerId/startCommandId/stopCommandId` 只读保留。`JobStatus` 增加非终态 `RECONCILING` 和 `UNKNOWN`；`PENDING/QUEUED/ALLOCATED → CANCELED` 可在未远程启动前取消，`STARTING/RUNNING → STOPPING → STOPPED` 需远端确认。`GET /train/jobs/{id}` 与现有日志/指标/告警/资源端点保持形状，新增 `serverId`、`serverName`、`executionMode`，旧字段对 SSH 任务可为 null。

---

### Task 1: SSH 任务、设备与租约的兼容 schema

**Files:** Create `backend/sql/gputrain_phase9_ssh_jobs.sql`、schema 测试；Modify `deploy/compose.yaml`、`JobRecord.java`、`JobMapper.java`/XML；Create `server/gpu` 下新设备、快照、租约记录与 Mapper/XML。

- [ ] **Step 1: 写迁移失败测试。** 断言 SQL 新增 `execution_mode/server_id/account_id/remote_unit_name` 且旧列保留；沿用 Phase 8 已创建的 `train_server_gpu_device/snapshot`，本次只新增 `train_server_gpu_lease` 的 `(server_id,gpu_uuid)` 活跃唯一键和 SSH 日志/指标表；旧 `train_job_log`、`train_job_metric` 和 Runner 表不被 DROP。测试 `RUNNER_LEGACY` 旧行仍可用 JobMapper 读取。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=SshJobSchemaContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 写增量迁移。** 旧 `train_job` 新列均可空，存量行回填 `RUNNER_LEGACY`；新行必须应用层校验 server/account。新 SSH 租约表独立于旧 Runner 租约表，避免改变现有外键和历史唯一约束；创建 `train_ssh_job_log/metric` 以绕开旧日志表的非空 Runner command 外键。活跃租约键以 `CONCAT(server_id,':',gpu_uuid)` 生成并唯一，生成列至少 `VARCHAR(160)`。Compose 初次初始化挂载为 `09-gputrain-ssh-jobs.sql`，已有 DB 按部署手册备份后手工执行。
- [ ] **Step 4: 绿灯与 MySQL 并发。** 跑 schema 测试；`-Pmysql-it` 测试同一服务器两个账户竞争同一卡只有一个成功、不同服务器同 UUID 可各自成功；重复执行迁移不删除旧行。
- [ ] **Step 5: 提交。** `feat: add SSH job and server-scoped GPU schema`。

### Task 2: 固定命令构造、远端持久单元和能力预检

**Files:** Create `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/job/{SystemdCommandBuilder,SystemdUnitState,SshRemoteJobClient}.java`、`backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/server/job/{SystemdCommandBuilderTest,SshRemoteJobClientTest}.java`；Modify `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/service/ServerInventoryService.java` 和 `server/ssh/ServerProbe.java` 的训练能力校验。

```java
record RemoteJobIdentity(long jobId, UUID jobUuid, long serverId, long accountId,
                         String unitName, String workingDirectory) {}
interface SshRemoteJobClient {
    RemoteUnitState inspect(RemoteJobIdentity job);
    RemoteUnitState start(RemoteJobIdentity job, FixedTrainingProfile profile, List<String> gpuUuids);
    RemoteUnitState stop(RemoteJobIdentity job);
}
```

- [ ] **Step 1: 写命令/能力测试。** 单元名固定为 `gputrain-<job UUID>.service`；profile 路径必须是账户允许根目录内的规范化绝对路径；用户输入不进入脚本路径、Shell 命令或 systemd 属性；重复 `start` 先查单元，已有单元不再创建；`systemd --user` 不可用/无法跨 SSH 会话保持运行/不支持 append 日志时 `trainable=false`。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=SystemdCommandBuilderTest,SshRemoteJobClientTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现远端调用。** 在账户根目录下建仅当前 Linux 用户可读的任务目录和日志文件，用 SFTP 写只含固定 profile 参数的单次任务脚本；通过经逐参数转义的固定 `systemd-run --user --unit=<unit> --property=KillMode=control-group --property=WorkingDirectory=<dir> --property=StandardOutput=append:<stdout> --property=StandardError=append:<stderr> /usr/bin/bash <script>` 启动。`inspect` 用固定 `systemctl --user show <unit>` 字段 `LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup`；`stop` 仅调用 `systemctl --user stop <unit>` 并再次 `inspect`。SSH 命令超时/非零退出不直接改任务为失败。
- [ ] **Step 4: 绿灯。** 单元测试与受控 Linux fixture（非 GPU）验证 SSH 断开后单元继续运行、退出码可读、停止不会碰同账户另一个单元；记录目标 systemd 版本/能力。
- [ ] **Step 5: 提交。** `feat: run fixed training profile in remote user systemd unit`。

### Task 3: 数据库调度、幂等启动与对账

**Files:** Modify `JobService.java`、`JobAdminController.java`、`JobStateMachine.java`、`JobStatus.java`、Mapper/XML；Create `server/job/{SshJobDispatcher,SshJobReconciler}.java` 及服务测试。

- [ ] **Step 1: 写失败测试。** 新提交只允许当前用户的 server/account、同一服务器 3 UUID、新鲜快照与可训练能力；两次相同 `requestKey` 返回同 job ID/单元名；资源不足时保留可见的 `QUEUED`、`startCommandId=null` 且无部分租约，至少 30 秒后再重试分配，用户可取消。模拟 SSH `start` 超时后单元实际存在：对账进入 RUNNING，启动调用仍恰好一次；远端不可达为 UNKNOWN 且保留租约；`RECONCILING/UNKNOWN` 不自动释放。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=SshJobDispatcherTest,SshJobReconcilerTest,JobServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现状态边界。** `submit` 在事务中保存 `QUEUED` 及用户/服务器约束，调度器通过 CAS 领取，原子选同服务器 3 GPU 并创建租约；资源不足写入原因并在 30 秒后重试，取消通过状态 CAS 与调度竞争。提交数据库事务后执行 `inspect → start`，不能在 DB 事务里等待 SSH。用同一 `unitName` 对账而非重复启动。`ActiveState=active` 为 RUNNING；成功退出为 SUCCEEDED；非零退出为 FAILED；SSH/远端状态不确定为 RECONCILING/UNKNOWN 并持有租约。保存操作审计及错误码，不把远端 stderr 中可能的秘密回传为公共异常。
- [ ] **Step 4: 绿灯和竞争测试。** 运行目标 JUnit、MySQL 租约并发测试；覆盖两个服务器同 UUID、同服务器两个用户、断线恢复、重复提交与调度器抢占。
- [ ] **Step 5: 提交。** `feat: dispatch and reconcile SSH training jobs`。

### Task 4: 安全停止、日志/指标/资源监控

**Files:** Create `server/job/SshLogCollector.java`、`train_ssh_job_log/metric` Mapper/XML；Modify `backend/ruoyi-train-core/pom.xml`（加入 `org.apache.sshd:sshd-sftp:2.20.0`）、`JobService.stop/cancel`、`JobObservabilityService.java`、`JobObservabilityController.java`、`GpuAdminController.java`；Create `SshJobStopTest.java`、`SshLogCollectorTest.java`、`JobObservabilityControllerTest.java`。

- [ ] **Step 1: 写失败测试。** `QUEUED/ALLOCATED` 且单元不存在时取消并释放未运行租约；RUNNING 停止请求后先 STOPPING，只有单元 inactive、相关 cgroup 无进程才 STOPPED/释放；SSH 断开或 `stop` 失败保留租约并进入待核对。日志偏移 `0,500,500,800` 只存两个增量块；旧 Runner 日志和新 SSH 日志都能从同一前端端点按事件 ID 获取，非 owner 被拒绝。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=SshJobStopTest,SshLogCollectorTest,JobObservabilityControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现监控。** 用 SFTP 按已确认 offset 读取 stdout/stderr，设置单次字节上限并只在 DB 写入成功后推进游标；新 SSH 日志表使用独立递增 ID，按 `executionMode` 路由现有日志历史/SSE 端点，指标解析继续复用 `TrainingMetricParser`、告警规则继续复用现有 evaluator。GPU 资源样本从同 server ID/任务租约期间的快照关联；状态不明的资源不显示为“空闲”。
- [ ] **Step 4: 实现停止并验证。** `stop` 仅向保存的 unitName 发送指令，`inspect` 确认后才终结；测试其它同账户单元仍在运行、重复停止幂等、断线后恢复对账；运行目标 JUnit/Maven 全量测试。
- [ ] **Step 5: 提交。** `feat: monitor and safely stop SSH jobs`。

### Task 5: 前端指定服务器的完整任务闭环

**Files:** Modify `frontend/src/api/train/{jobs,devices,servers}.ts`、`frontend/src/views/train/jobs/index.vue`、`frontend/src/views/train/jobs/{jobActions,jobSelection,jobLogStream}.ts`；Create/modify对应 Vitest。

- [ ] **Step 1: 写失败测试。** 请求含 `serverId/accountId/allowedGpuUuids`；切换服务器清空已选 GPU 和旧监控内容；仅显示该 server ID 的 GPU；不可训练能力时禁用提交并显示具体检查项；`UNKNOWN/RECONCILING/QUARANTINED` 不显示“已停止”；断线重连不重复日志。
- [ ] **Step 2: 运行红灯。** `npm --prefix frontend test -- jobs.test.ts jobSelection.test.ts jobActions.test.ts jobLogStream.test.ts`。
- [ ] **Step 3: 接入页面。** 表单第一项选服务器，第二项选本人可用 Linux 账户，然后选三张同服务器 GPU；列表新增服务器列与状态文字；详情显示服务器、远端单元与最后核对时间。停止弹窗写明任务和目标服务器，成功响应仅提示“停止请求已发出”，以最终 STOPPED 为准。
- [ ] **Step 4: 全量验证。** `npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build:prod`、`git diff --check`；浏览器检查 0/1/2 台服务器、离线、无权限、窄屏和表单切换。
- [ ] **Step 5: 提交。** `feat: submit and monitor SSH jobs by server`。

### Task 6: 真实 SSH 训练与故障恢复验收

**Files:** Create `docs/verification/ssh-job-migration.md`；Modify `docs/operations/deployment-and-recovery.md`、`README.md`。

- [ ] **Step 1: 预检与备份。** 在目标服务器记录用户级 systemd 持久性、脚本/模型路径、3 个获授权 GPU 的 UUID/显存/外部进程、现有任务和租约；备份 DB/JAR。迁移 Phase 9 后确认旧 Runner 历史行仍可读。
- [ ] **Step 2: 受控训练。** 用密码连接一台、私钥连接另一台；在一台服务器提交 1-step 任务，记录 job ID、unitName、状态、日志/指标/资源、远程进程退出与租约；另一台做只读状态核对。两台同 UUID 的测试可用数据库/fixture 完成，不要求制造真实 UUID 冲突。
- [ ] **Step 3: 停止与故障注入。** 用获授权长任务验证停止确认、远程单元停止、进程退出和租约释放；在另一个受控任务中断开控制面到 SSH 的连接，确认 UNKNOWN/RECONCILING 且未重复启动，恢复后核对真实单元与日志游标。
- [ ] **Step 4: 全量门槛与记录。** `mvn -f backend/pom.xml test`、`mvn -f backend/pom.xml -pl ruoyi-train-core test -Pmysql-it`、前端 test/typecheck/build、`git diff --check`；记录失败项和回滚检查。所有旧 Runner 活跃任务及租约清零前不得进入 Task 7。
- [ ] **Step 5: 提交。** `docs: verify agentless SSH training and recovery`。

### Task 7: Runner 退役与历史只读兼容

**Files:** Modify `backend/pom.xml`、`backend/ruoyi-train-core/pom.xml`、`backend/ruoyi-admin/pom.xml`、`backend/sql/gputrain_menu.sql`、`README.md`、`docs/operations/deployment-and-recovery.md`；Delete `backend/ruoyi-train-runner/**`、`backend/ruoyi-train-runner-protocol/**`、`backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/{web,service,security,config}/**`、`deploy/runner/**`；把历史日志读取所需的 Runner Mapper 移到 `job/legacy` 只读包，删除其它 Runner Mapper/XML。

- [ ] **Step 1: 写保留历史测试。** 构造 `RUNNER_LEGACY` 终态任务、旧日志/指标/告警，断言旧 API 仍可读且不能再次执行或停止；构造 SSH 任务断言完全不引用 Runner 命令。`rg -n 'ruoyi-train-runner|runner-api|train:runner:' backend frontend deploy` 列出待删引用并逐项分类。
- [ ] **Step 2: 运行红灯。** 先删除执行路径的测试依赖或切换为 SSH，运行目标 JUnit，确认尚有 Runner 耦合待解。
- [ ] **Step 3: 删除运行时代码。** 将还需使用的 `ProcessSpec`/GPU/日志解析值类型移入 core 的中性包；删除 Runner 注册、心跳、长轮询、命令/进程回报接口及前端菜单、POM 模块和部署包。历史数据库表/列与只读 Mapper 保留，移除启动期 Runner 凭据要求；不执行 DROP。
- [ ] **Step 4: 绿灯。** 全量 Maven test/package、前端 test/typecheck/build、Compose config 与真实旧任务读取；`rg` 只允许历史迁移/验收文档及 `RUNNER_LEGACY` 只读兼容引用，不能残留可调用 Runner API。
- [ ] **Step 5: 提交。** `refactor: retire Runner runtime after SSH migration`。记录 Runner 服务已停机且其旧任务进程已退出；如果任一门槛未过，保留 Runner 运行时代码并先修复。

## 计划自检

- SSH 多服务器、密码/私钥、逐台状态、固定训练、日志/指标/告警、停止、断线恢复、权限/租约及 Runner 删除均有明确交付与验证。
- 先交付独立可测的服务器连接计划；本计划依赖其稳定 API。数据库以增量表和 nullable 新列保留旧数据，Runner 删除在真实执行通过之后。
