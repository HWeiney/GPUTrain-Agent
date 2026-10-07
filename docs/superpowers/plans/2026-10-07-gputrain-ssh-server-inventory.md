# GPUTrain SSH Server Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在本机或服务器部署的控制面上，通过 SSH 密码或私钥管理多台物理服务器及用户账户，并在页面显示可验证、带采样时间的服务器与 GPU 状态。

**Architecture:** 浏览器只访问 Spring Boot 控制面。控制面持有物理服务器与用户 SSH 账户目录，以固定主机公钥和加密凭据建立 SSH 会话；有界探测任务写入状态快照。此计划只交付服务器连接与只读状态，训练执行由后续 `2026-10-07-gputrain-ssh-job-migration.md` 接入。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis、MySQL、Vue 3、Element Plus、Vitest；Apache MINA SSHD `2.20.0` 的 `sshd-core`（客户端与嵌入式测试服务端）。

## Global Constraints

- `server_id` 标识物理服务器，`account_id` 标识若依用户在该服务器的 Linux 账户；普通用户只能访问自己的账户。
- 密码、私钥、私钥口令和加密密文绝不出现在读取 API、日志、审计详情或前端持久存储；主密钥仅来自控制面环境配置。
- 非回环浏览器访问控制面必须使用 HTTPS；SSH 凭据只发送到本系统控制面，不交给第三方页面。
- 主机公钥必须先展示并确认，指纹变化时在认证前拒绝；不得使用无条件信任主机密钥的 SSH verifier。
- SSH 连接、命令和读取都有超时、大小上限及有界并发。连接失败保留最后成功快照及其时间，但不能标为在线或可调度。
- 远程命令仅为固定白名单；不提供通用终端、浏览器直连 SSH、任意 Shell 参数或自动 sudo。
- 现有 Runner API、任务和旧数据库列在此计划执行期间保持可用；不得在本阶段删除。

## 文件与接口地图

| 职责 | 文件 |
|---|---|
| 增量迁移 | `backend/sql/gputrain_phase8_ssh_servers.sql`、`deploy/compose.yaml` |
| 服务器/账户读模型 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/{domain,mapper,service,web}`、`backend/ruoyi-train-core/src/main/resources/mapper/train/{ServerMapper,ServerAccountMapper,ServerSnapshotMapper}.xml` |
| SSH 边界 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/ssh/{SshGateway,MinaSshGateway,ServerSecretCipher,ServerProbe}.java` |
| 前端 | `frontend/src/api/train/servers.ts`、`frontend/src/views/train/servers/index.vue`、`frontend/src/views/train/overview/index.vue`、`frontend/src/views/train/devices/index.vue` |
| 菜单、验收 | `backend/sql/gputrain_menu.sql`、`docs/verification/ssh-server-inventory.md`、`README.md` |

跨任务契约：`ServerView` 仅包含 `serverId,name,host,port,hostKeyFingerprint,enabled,lastProbeAt,lastSuccessAt,status,capabilities`；`ServerAccountView` 仅包含 `accountId,serverId,ownerUserId,linuxUsername,authType,hasCredential,allowedRoot`。`status` 为 `ONLINE|DEGRADED|OFFLINE|HOST_KEY_CHANGED|AUTH_FAILED|UNKNOWN`；`capabilities` 分别记录 `ssh,gpu,systemdUser,persistentUserManager`，不能用一个布尔值替代。`ServerProbeResult` 包含 `serverId,accountId,sampledAt,load1,memoryTotalKiB,memoryAvailableKiB,diskTotalKiB,diskAvailableKiB,gpus,errorCode`。API：`POST /train/servers/host-key-preview`、`POST /train/servers`、`GET /train/servers`、`GET /train/servers/{id}`、`POST /train/servers/{id}/accounts`、`POST /train/servers/{id}/probe`、`PUT /train/servers/{id}/accounts/{accountId}`、`GET /train/servers/{id}/devices`。所有详情/探测接口按当前用户账户权限过滤。

---

### Task 1: 物理服务器、账户与快照的增量 schema

**Files:** Create `backend/sql/gputrain_phase8_ssh_servers.sql`、`backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/server/domain/{ServerRecord,ServerAccountRecord,ServerSnapshotRecord,ServerGpuDeviceRecord,ServerGpuSnapshotRecord}.java`、对应 Mapper/XML、`backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/server/ServerSchemaContractTest.java`；Modify `deploy/compose.yaml`。

- [ ] **Step 1: 写 schema 契约失败测试。** 读取 SQL 并断言 `train_server`、`train_server_account`、`train_server_snapshot`、`train_server_gpu_device`、`train_server_gpu_snapshot`、`UNIQUE KEY uk_server_account (server_id,owner_user_id,linux_username)`、`UNIQUE KEY uk_server_gpu (server_id,gpu_uuid)`、`host_key_sha256`、`credential_ciphertext`、`last_success_at` 和账户外键存在；断言读取 Mapper 的 SELECT 不包含 `credential_ciphertext`。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=ServerSchemaContractTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期缺 SQL/Mapper。
- [ ] **Step 3: 建表与 Mapper。** `train_server` 用 `server_id BIGINT` 主键及 `host VARCHAR(255), port INT, host_key_sha256 VARCHAR(128), enabled, created_at, updated_at`，`UNIQUE(host,port)` 防重复登记；`train_server_account` 用 `account_id` 主键、`server_id/owner_user_id/linux_username/auth_type/credential_ciphertext/passphrase_ciphertext/allowed_root/profile_json`；`train_server_snapshot` 用 `(server_id,account_id,sampled_at)` 索引、状态字段和受限大小的事实 JSON；`train_server_gpu_device` 以 `(server_id,gpu_uuid)` 唯一，`train_server_gpu_snapshot` 按 `(server_id,account_id,gpu_uuid,sampled_at)` 建索引。列表 Mapper 只投影无秘密字段；密文只允许按已授权 account ID 单行查询。Compose 将迁移挂到 `/docker-entrypoint-initdb.d/08-gputrain-ssh-servers.sql`，现有数据库使用手工迁移。
- [ ] **Step 4: 跑绿灯并做真实 MySQL 结构检验。** 运行 Task 1 测试、`docker compose --env-file .env.example -f deploy/compose.yaml config -q`；若本地 MySQL 可用，以 `-Pmysql-it` 验证重复迁移和唯一约束。
- [ ] **Step 5: 提交。** `git diff --check` 后提交 `feat: add SSH server inventory schema`。

### Task 2: 主机指纹、凭据加密与 SSH 传输

**Files:** Modify `backend/ruoyi-train-core/pom.xml`、配置样例；Create `server/ssh/{SshGateway,MinaSshGateway,ServerSecretCipher,HostKeyFingerprint}.java` 及 `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/server/ssh/{ServerSecretCipherTest,MinaSshGatewayTest}.java`。

```java
interface SshGateway {
    HostKeyPreview preview(String host, int port, Duration timeout);
    <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account,
            Function<SshSession, T> operation);
}
record HostKeyPreview(String algorithm, String sha256) {}
interface SshSession extends AutoCloseable {
    CommandResult exec(List<String> fixedCommand, Duration timeout, int maxBytes);
}
```

- [ ] **Step 1: 写加密/握手测试。** 固定 32 字节测试主密钥；同一密码两次密文不同且均可解密，错误密钥解密失败。嵌入式 SSH 服务用密码和私钥分别登录；未确认指纹时禁止认证，指纹换掉后拒绝并断言认证回调调用次数为 0，连接超时有专用错误码。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=ServerSecretCipherTest,MinaSshGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现传输边界。** Maven 固定 `org.apache.sshd:sshd-core:2.20.0`；密文使用 AES-256-GCM 和随机 96 位 IV，版本前缀明确；`preview` 只完成无凭据公钥协商，`withVerifiedSession` 使用保存的完整公钥/指纹做常数时间比较后才认证。支持密码及加密/未加密私钥，私钥口令单独解密；禁止日志输出命令中的凭据、远程 stdout 中的敏感配置。连接/认证/命令分别设定 5/10/15 秒默认超时和 1 MiB stdout/stderr 上限。
- [ ] **Step 4: 跑绿灯。** 两个目标测试通过；检查依赖树没有旧的易受攻击 SSH 实现，`git diff --check`。
- [ ] **Step 5: 提交。** `feat: add verified SSH transport and encrypted credentials`。

### Task 3: 服务器状态探测与 GPU 事实

**Files:** Create `server/ssh/{ServerProbe,SshNvidiaSmiParser}.java`、`server/service/{ServerProbeService,ServerDeviceQueryService}.java`、`server/domain/ServerProbeResult.java` 及 `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/server/{ssh/ServerProbeTest,ssh/SshNvidiaSmiParserTest,service/ServerProbeServiceTest}.java`；Modify `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/gpu/web/GpuAdminController.java`。

- [ ] **Step 1: 写探测解析测试。** 固定 `/proc/loadavg`、`/proc/meminfo`、`df -Pk` 和现有 Runner 使用的 `nvidia-smi --query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version --format=csv,noheader,nounits`、`--query-compute-apps=gpu_uuid,pid,process_name,used_memory` 样例；将 CSV 解析移植到 `SshNvidiaSmiParser`，断言外部进程数、MIG、两台 server ID 的相同 UUID 不混淆，`nvidia-smi` 不存在只令 `gpu=false`，旧快照的 `sampledAt` 不被当前探测失败覆盖。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=ServerProbeTest,ServerProbeServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现白名单采集。** `ServerProbe` 只执行上述固定命令及 `systemctl --user is-system-running`/持久用户管理器预检，限制输出与解析范围；`SshNvidiaSmiParser` 保持原有 CSV 字段与状态判定，不能把未知进程当成空闲；`ServerProbeService.probe(serverId, accountId)` 将成功快照与 `last_success_at` 写入数据库。失败只更新 `last_probe_at,status,error_code`，不覆盖最后成功值。后台调度每账户至少 10 秒间隔、最多 8 个并发 SSH 会话，超时按账户退避，离线时不重复紧密重试。
- [ ] **Step 4: 跑绿灯。** 加一个 2 台服务器、同 UUID、一个离线的服务测试，断言在线状态与快照隔离；执行目标 JUnit 与 `git diff --check`。
- [ ] **Step 5: 提交。** `feat: collect bounded server and GPU snapshots over SSH`。

### Task 4: 服务器 API、权限和连接生命周期

**Files:** Create `server/web/ServerAdminController.java`、`server/service/ServerInventoryService.java`、对应 Controller/Service 测试；Modify `backend/sql/gputrain_menu.sql`、`backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/overview/TrainOverviewService.java`。

- [ ] **Step 1: 写权限/响应测试。** 普通用户仅见本人 `ServerAccountView`；无 `train:server:manage` 不能新增/修改；管理员看服务器元数据但不能用别人账户探测；`GET` 序列化结果不含 `credentialCiphertext`、密码、私钥或口令；错误主机指纹返回 `HOST_KEY_CHANGED`，认证失败返回 `AUTH_FAILED`。
- [ ] **Step 2: 运行红灯。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=ServerAdminControllerTest,ServerInventoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现 API。** 新增 `train:server:view/manage` 菜单权限；只有管理员创建物理 `train_server` 并为若依用户登记 `train_server_account`，普通用户只能查看和更新本人账户的凭据。创建请求中 `host` 为 DNS 名或 IP、端口 1–65535、指纹需与预览一致、工作根目录必须为绝对路径；相同 `host:port` 拒绝重复创建，发现相同指纹的不同地址时要求管理员核对是否为同一物理机，避免重复 server ID 导致双重租约。编辑账户留空秘密保持原值；显式 rotate 请求才能更换秘密。删除采用 `enabled=false`，若有非终态任务则拒绝。总览改为在线服务器数，响应增加 `onlineServers`，保留 `onlineRunners` 过渡字段直到第二计划完成。
- [ ] **Step 4: 跑绿灯。** MVC/Service 测试、MyBatis XML 加载、`git diff --check`；确认匿名访问拒绝且响应无秘密。
- [ ] **Step 5: 提交。** `feat: expose owner-scoped SSH server inventory API`。

### Task 5: 前端服务器清单和状态视图

**Files:** Create `frontend/src/api/train/servers.ts`、`frontend/src/api/train/servers.test.ts`、`frontend/src/views/train/servers/index.vue`；Modify `frontend/src/views/train/{overview,devices}/index.vue`、`frontend/src/api/train/overview.ts`。

- [ ] **Step 1: 写 API/视图规则测试。** 断言读取类型无秘密字段；密码/私钥仅在提交请求中出现；主机指纹未确认时“保存”禁用；`OFFLINE` 显示最后成功时间而非旧数据“在线”；选择 server ID 后设备接口带 `serverId`。
- [ ] **Step 2: 运行红灯。** `npm --prefix frontend test -- servers.test.ts serverStatus.test.ts`。
- [ ] **Step 3: 实现页面。** 清单列出名称、地址、账号、状态、最后成功时间和能力；添加向导按“地址预览指纹 → 人工确认 → 密码或私钥/口令 → 测试连接 → 保存”顺序，秘密输入框不自动保存且关闭即清空。详情按服务器显示 CPU load、内存、磁盘、GPU；设备页添加服务器筛选，快照过期显示明确标签。工作台按新 `onlineServers` 字段显示。
- [ ] **Step 4: 验证。** `npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build:prod`、`git diff --check`；用浏览器检查窄屏表单和匿名/无权限按钮。
- [ ] **Step 5: 提交。** `feat: manage SSH servers and show live status`。

### Task 6: 第一阶段真实联调与文档

**Files:** Create `docs/verification/ssh-server-inventory.md`；Modify `README.md`、`docs/operations/deployment-and-recovery.md`。

- [ ] **Step 1: 准备两台授权 Linux 主机。** 记录别名、SSH 端口、主机公钥指纹、系统/`nvidia-smi`/`systemd --user` 能力，不把密码、私钥、令牌写入记录；备份数据库后执行 `gputrain_phase8_ssh_servers.sql`。
- [ ] **Step 2: 浏览器联调。** 从本机控制面用一台密码、一台私钥连接；检查指纹确认、错误凭据、主机密钥变化拒绝、离线/重连及不同服务器同 GPU UUID 的隔离。服务器部署控制面重复至少一台连接，证明页面拓扑不变。
- [ ] **Step 3: 回归与记录。** `mvn -f backend/pom.xml test`、`npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build:prod`；验收文档写实际环境、时间、结果与失败项，核对数据库/API/日志无明文秘密。
- [ ] **Step 4: 提交。** `docs: verify agentless multi-server inventory`。此阶段不停止现有 Runner，也不将 SSH 状态页误报为已完成训练迁移。

## 计划自检

- 设计中的物理服务器/用户账户、双认证、指纹确认、加密、权限、状态采样、多服务器隔离及本机/服务器两种控制面部署均有对应任务。
- 训练执行、日志、租约和 Runner 删除由下一计划接续；第一阶段仍是可独立验收的状态产品。
