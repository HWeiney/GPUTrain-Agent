# GPUTrain Agent 阶段二设计：用户级 Training Runner

## 1. 目标与验收边界

阶段二实现用户级 Training Runner 的最小可靠闭环：Runner 完成一次性注册，使用 HMAC 签名发送心跳和长轮询请求，领取持久化命令，在限定目录内安全启动测试进程，并在 Runner 重启后重新关联仍存活的进程。

阶段二以以下结果作为退出条件：

- Runner 可以绑定若依用户、Linux 用户名和唯一允许目录。
- 一次性注册凭据只能在有效期内成功消费一次。
- 注册后的请求可验证时间戳、Nonce、正文摘要和 HMAC-SHA256 签名。
- 控制面能持久化命令并通过长轮询租给指定 Runner。
- Runner 不经过 Shell，在允许目录内启动、观察和停止安全 fixture 进程。
- 重复领取或重复上报不会重复启动进程或破坏最终状态。
- Runner 重启后能根据 PID、启动时间和命令信息恢复进程关联。
- 在 Linux 主机 `10.31.118.177` 的普通用户 `hanw` 下，使用 `/data/hanw/hw_code/gputrain-test` 完成真实验收。

本阶段不实现 GPU 发现与分配、完整训练任务状态机、日志 SSE、训练指标、Qwen Agent、MCP 或 Runner 管理前端。这些能力仍按总路线图在后续阶段完成。

## 2. 架构与模块边界

系统继续采用“模块化单体控制面 + 用户级原生 Runner”架构。控制面不通过 SSH 登录训练服务器，Runner 主动连接控制面，因此控制面不保存 Linux 密码或 SSH 私钥。

```text
RuoYi 控制面
  |-- Runner 注册与身份认证
  |-- 心跳与在线状态
  |-- 持久化命令队列
  `-- 进程状态记录
          ^
          | HTTPS + HMAC
          | 注册、心跳、长轮询、结果上报
          v
用户级 Java Runner
  |-- 本地配置读取
  |-- 请求签名
  |-- 安全策略校验
  |-- ProcessBuilder 执行
  `-- PID 与进程恢复
          |
          v
/data/hanw/hw_code/gputrain-test
```

模块职责如下：

- `ruoyi-train-runner-protocol`：纯 Java 协议模块，定义注册、心跳、命令、执行结果、进程快照和稳定错误码，不依赖 Spring。
- `ruoyi-train-core`：控制面 Runner 领域实现，包括元数据、注册凭据、HMAC 验证、心跳、命令租约、幂等和持久化接口。
- `ruoyi-train-runner`：新增的独立可执行 Java 模块，只依赖协议模块及必要的 HTTP、JSON 和日志组件，不依赖若依 Web 应用。
- `ruoyi-admin`：继续作为控制面启动入口，加载 `ruoyi-train-core` 暴露的管理接口和 Runner 接口。

前端在本阶段不新增页面。控制面通过受保护的管理 API 创建注册凭据和安全 fixture 命令，避免在真实数据链路完成前建设空壳界面。

## 3. 迭代切片

阶段二按六个可独立测试和提交的切片推进：

1. Runner 通信协议、命令和状态模型。
2. 一次性注册、凭据保存和 HMAC 请求认证。
3. 心跳、在线状态和长轮询命令领取。
4. 允许目录内的安全 fixture 进程启动与停止。
5. Runner 重启后的本地状态加载与进程对账。
6. `systemd --user` 部署材料、Linux 验收和阶段文档。

每个切片遵循“失败测试、最小实现、模块验证、代码走读、提交”的开发链路，不把多个无法独立解释的业务能力压入同一次提交。

## 4. 注册与凭据生命周期

管理员通过若依登录态和专用权限创建一次性 Runner 注册凭据。请求明确指定所属若依用户、Linux 用户名、Runner 名称、唯一允许目录、有效期和必填幂等键。控制面生成高熵随机凭据，并保存 HMAC 摘要以及使用 AES-256-GCM 加密的临时副本。相同幂等键和相同绑定字段的重试返回同一个凭据；字段不同则返回稳定的注册错误，不创建新记录。唯一幂等键约束处理并发重复创建，冲突方读取并返回已提交的获胜记录。

临时凭据密文只用于让创建请求在响应丢失后安全重试。注册成功时，条件消费语句同时设置消费时间并清空该密文；Runner 创建失败时，事务回滚消费状态和密文清除。消费后的幂等键不能再恢复注册凭据，而是返回已消费错误。数据库任何时刻都不保存明文注册凭据。创建与注册事务使用 `READ_COMMITTED`，使唯一键竞争后的 winner reread 和并发消费后的状态分类能看到已提交结果。

Runner 首次启动时提交一次性凭据及本机身份。控制面用恒定时间比较验证摘要，并在同一事务中把凭据标记为已消费、创建 Runner 记录并签发独立 Runner 密钥。并发重复注册只能有一个请求成功。

Runner 密钥不写入 Java 源码、Git、命令行参数或日志。Runner 将它写入权限为 `600` 的配置文件；控制面使用环境变量提供的 256 位主密钥，以 AES-256-GCM 和每条记录独立随机 IV 加密保存。若主密钥缺失或长度不合法，生产配置应快速失败，不允许回退到明文存储。

注册凭据和后续协议正文都可能包含敏感信息，因此真实网络请求必须使用 HTTPS；HMAC 只提供身份认证与完整性，不提供传输加密。两个携带注册凭据的端点都检查传输安全：HTTPS 总是允许；HTTP 只有在显式本地/测试配置启用回环 HTTP 且远端地址是回环地址时才允许。即使本地配置启用，非回环 HTTP 仍返回稳定错误 `RUNNER_HTTPS_REQUIRED`。Runner 协议异常由高优先级的 Runner 专用异常处理器返回 JSON `{errorCode, message}`；`RUNNER_HTTPS_REQUIRED` 使用 HTTP 403，其他协议拒绝使用 HTTP 400。消息为固定安全文本，不透出异常详情。生产配置不允许明文 HTTP。

阶段二不依赖 SSH 完成运行时通信。SSH 仅用于首次把构建产物和配置部署到 Linux 主机；在未配置 SSH 公钥的情况下，由用户在服务器终端执行生成好的部署命令和验收命令。

## 5. HMAC 请求认证

注册后的 Runner 请求使用以下规范字符串生成 HMAC-SHA256：

```text
runnerId + "\n"
+ timestamp + "\n"
+ nonce + "\n"
+ httpMethod + "\n"
+ requestPath + "\n"
+ sha256(requestBody)
```

请求头携带 Runner ID、Unix 时间戳、随机 Nonce 和 Base64URL 编码签名。控制面按以下顺序验证：

1. 必需请求头存在且格式正确。
2. 时间戳位于允许时钟偏差内。
3. Runner 存在、启用且凭据可以解密。
4. Nonce 尚未被使用。
5. 使用原始请求正文计算摘要并以恒定时间比较签名。
6. 验证签名后，通过 Redis `SET NX EX` 原子记录 Nonce；键已存在即判定为重放。

Nonce 使用 Redis 保证短期防重放。对心跳、领命令、启动确认、停止确认和对账等 Runner 接口，Redis 不可用时拒绝请求，不进行不安全降级。完整密钥、主密钥、签名和敏感环境变量不得进入应用日志。

## 6. 持久化模型

阶段二引入四类核心记录：

### 6.1 `train_runner`

保存 Runner ID、显示名称、所属若依用户 ID、Linux 用户名、允许根目录、加密凭据、版本、状态、最后心跳时间、注册时间和禁用时间。

### 6.2 `train_runner_enrollment`

保存注册凭据摘要、临时 AES-GCM 密文、唯一幂等键、绑定用户、Linux 用户名、允许根目录、有效期、消费时间和创建人。幂等键使用 `utf8mb4_0900_bin` 二进制排序规则，并与凭据摘要分别具有唯一约束。凭据只能由单个事务从未消费状态转为已消费状态；该条件更新会同时清空临时密文，Runner 创建失败时事务回滚会恢复密文。

`backend/sql/gputrain_runner.sql` 同时作为全新初始化和中间版本数据库升级脚本使用。它通过 `INFORMATION_SCHEMA` 条件检查添加缺失的临时密文列和幂等键列，为历史记录生成以 enrollment ID 区分的迁移键，将幂等键改为非空且二进制排序规则，并仅在缺少时创建唯一索引。重复执行不会重复添加列或索引。

### 6.3 `train_runner_command`

保存命令 ID、Runner ID、类型、结构化负载、幂等键、状态、租约截止时间、尝试次数、结果、错误码、创建时间和更新时间。`runner_id + idempotency_key` 具有唯一约束。

命令状态为：

```text
QUEUED -> LEASED -> RUNNING -> SUCCEEDED
                         `-> FAILED
                         `-> STOPPED
       `-> REJECTED
```

租约超时只允许尚未确认启动的命令重新进入可领取状态。已经报告 `RUNNING` 的命令必须通过进程对账确定状态，不能因 HTTP 租约过期而重新启动。

### 6.4 `train_runner_process`

保存命令 ID、Runner ID、PID、进程启动时间、工作目录、可执行文件、参数摘要、stdout 路径、stderr 路径、状态、退出码和最后对账时间。它是控制面观察到的进程事实，不替代 Runner 本地状态文件。

MySQL 是 Runner、命令和进程状态的持久化事实来源；Redis 只保存 Nonce 和短期在线状态，不承担命令可靠性。

## 7. API 与数据流

控制面提供两类接口：

- 管理接口：沿用若依登录态与权限注解，用于创建注册凭据、查询 Runner、创建安全 fixture 命令和请求停止。
- Runner 接口：使用独立 HMAC 认证，不接受若依用户 Token，用于注册、心跳、长轮询、执行状态上报和进程对账。

除一次性凭据注册接口外，Runner 接口在验证 HMAC 前执行 HTTPS 检查；只有明确启用本地开发例外时才允许 loopback HTTP。签名请求的原始 body 上限由 `gputrain.runner-security.max-request-body-bytes` 配置，默认 65536 字节。已知 Content-Length 超限时在读取前拒绝；未知长度请求最多读取上限加 1 字节，并以安全 JSON 和 HTTP 413 返回 `RUNNER_REQUEST_TOO_LARGE`。注册接口不受该签名请求上限约束。

心跳先更新 MySQL 的 `last_seen_at`，再以 90 秒 TTL 写入 Redis 在线标记。若在线标记写入失败，返回 HTTP 503 和 `RUNNER_TEMPORARILY_UNAVAILABLE`，不暴露 Redis 详情；此时 MySQL 更新时间可能已提交，重试心跳是幂等的。

主要数据流如下：

```text
管理员创建安全 fixture 命令
  -> MySQL 保存 QUEUED 命令
  -> Runner 长轮询
  -> 控制面原子地把一条命令更新为 LEASED
  -> Runner 执行本地安全校验
  -> Runner 启动进程并原子写入本地状态
  -> Runner 上报 RUNNING
  -> 进程退出后上报 SUCCEEDED / FAILED / STOPPED
```

长轮询在超时且无命令时返回空响应。网络超时后 Runner 使用退避和抖动重试；命令 ID 与幂等键使重复领取、重复启动确认和重复最终结果上报返回已有状态，而不是产生第二个进程。

稳定错误码至少包括：

- `RUNNER_AUTH_INVALID`
- `RUNNER_REPLAY_DETECTED`
- `RUNNER_CLOCK_SKEW`
- `ENROLLMENT_INVALID`
- `ENROLLMENT_ALREADY_USED`
- `RUNNER_HTTPS_REQUIRED`
- `RUNNER_REQUEST_TOO_LARGE`
- `RUNNER_TEMPORARILY_UNAVAILABLE`
- `COMMAND_LEASE_EXPIRED`
- `WORK_DIR_OUTSIDE_ALLOWED_ROOT`
- `WORK_DIR_SYMLINK_ESCAPE`
- `EXECUTABLE_NOT_ALLOWED`
- `ENVIRONMENT_NOT_ALLOWED`
- `PROCESS_ALREADY_STARTED`
- `PROCESS_IDENTITY_MISMATCH`

## 8. 安全进程执行

Runner 不接受任意 Shell 字符串，只接受结构化进程规格：

```text
workingDirectory
executable
arguments[]
environment
timeout
idempotencyKey
```

执行前必须完成以下检查：

1. 工作目录存在，并在解析真实路径后仍位于配置的允许根目录内。
2. 符号链接不能把工作目录、入口文件、日志文件或状态目录引到允许根目录之外。
3. 可执行文件匹配本地配置的绝对路径白名单。
4. 环境变量名称在本地白名单内；控制面不能覆盖 `PATH`、动态链接器变量或 Runner 凭据。
5. 参数作为独立元素传给 `ProcessBuilder`，不使用 `sh -c`、字符串拼接或 Shell 展开。
6. 幂等键已有本地状态时返回原进程信息，不重新启动。
7. stdout、stderr 和元数据只写入允许根目录下的 `.gputrain/` 子目录。

停止操作只允许作用于 Runner 本地记录且 PID 身份匹配的进程。重复停止已退出进程返回已有最终状态。对于无法确认身份的 PID，Runner 返回 `PROCESS_IDENTITY_MISMATCH`，不得终止该进程。

阶段二使用无 GPU 副作用的 fixture 验证正常完成、失败退出、长时间运行和停止；真实训练命令在第三阶段资源租约与任务状态机完成后接入。

## 9. 本地状态与进程恢复

Runner 在进程成功创建后，将命令 ID、幂等键、PID、进程启动时间、工作目录、可执行文件、参数摘要和日志路径写入临时文件，刷新后原子替换正式状态文件。状态目录位于允许根目录下的 `.gputrain/state/`。

Runner 重启时扫描本地状态：

- PID 存活且启动时间、可执行文件和命令摘要匹配时，恢复为 `RUNNING`。
- 进程已退出且本地已有最终状态时，恢复相同最终状态。
- PID 不存在且没有可信退出记录时，标记为 `UNKNOWN` 并上报控制面。
- PID 存活但身份字段不匹配时，标记为身份冲突，不观察、不停止该进程。

Runner 随后向控制面提交进程快照。控制面按照命令 ID 和版本进行幂等对账，不因 Runner 短暂离线直接把运行中命令重新入队。

## 10. 错误处理与可观测性

协议错误使用稳定业务错误码，日志保留 Runner ID、命令 ID、阶段和非敏感摘要。控制面和 Runner 均生成关联 ID，便于把一次领取、启动和上报串联起来。

错误按处理方式分为：

- 永久拒绝：签名错误、目录越界、可执行文件不允许、身份冲突。
- 可重试：网络中断、长轮询超时、控制面临时不可用。
- 需要对账：启动后上报超时、Runner 重启、控制面重启、状态不一致。

重试采用有上限的指数退避与抖动。安全校验失败和业务拒绝不得自动重试。日志不得包含注册明文凭据、Runner 密钥、完整签名、环境变量值或未经限制的命令负载。

## 11. 测试与验收

开发采用失败测试优先，测试分层如下：

- 协议单元测试：必填字段、状态、序列化、规范签名字符串和错误码。
- 凭据测试：一次性消费、并发消费、过期、带认证加解密和日志脱敏。
- HMAC 测试：正确签名、正文篡改、过期时间戳、Nonce 重放和恒定时间验证边界。
- 命令测试：原子领取、租约、幂等创建、重复确认和最终状态不可逆。
- 安全测试：目录穿越、符号链接逃逸、Shell 元字符参数、环境变量越权和 PID 身份不匹配。
- Runner 测试：成功、失败、停止、重复领取、本地状态原子写入和重启恢复。
- 回归测试：完整 Maven Reactor、现有前端测试与生产构建、Compose 配置和 `git diff --check`。

Linux 验收限定在 `hanw` 用户和 `/data/hanw/hw_code/gputrain-test`：

1. 部署 Runner 可执行包和权限为 `600` 的配置。
2. 使用 `systemd --user` 启动 Runner。
3. 完成注册和心跳，确认控制面显示在线。
4. 执行短时 fixture 并验证成功状态与日志。
5. 执行长时 fixture，在进程存活期间重启 Runner。
6. 验证 Runner 重新关联原 PID，没有产生重复进程。
7. 发送停止请求并验证幂等停止与最终状态。
8. 验证允许目录之外没有新增或修改文件。

验收不操作 root 权限、不终止既有进程，也不访问指定目录之外的训练数据。

## 12. 开发沟通与文档

代码、测试、部署材料和验证由 Codex 完成。每个切片提交后向用户说明：

```text
本次目标
修改文件
关键调用链
设计原因
测试证据
Git 提交
面试表达
```

阶段完成时更新：

- `README.md`：Runner 配置、构建、启动和安全边界。
- `docs/verification/phase-2-runner.md`：自动测试与 Linux 验收证据。
- `docs/interview/项目面试考点.md`：Runner 身份、主动连接、安全执行、幂等和进程恢复问题。

任何未在代码和验证中完成的能力继续标记为规划，不写成已实现成果。
