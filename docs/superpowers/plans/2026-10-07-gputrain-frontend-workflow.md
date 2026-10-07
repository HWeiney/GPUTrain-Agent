# GPUTrain Frontend Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从本机前端连接服务器控制面，可靠完成 GPU/Runner 状态确认、固定训练提交、监控及取消/停止。

**Architecture:** 开发时 Vite 将 `/dev-api` 代理到配置的服务器 HTTPS 控制面；正式环境由服务器反向代理 `/prod-api`。Runner 继续主动连接服务器控制面。控制面负责真实状态、权限和任务状态转换；前端负责明确展示及操作反馈。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Vite、Vitest；Spring Boot 3、MyBatis、MySQL、Redis、JUnit 5。

## Global Constraints

- 唯一可提交的 profile 为 `docowl2-srfund`，`maxSteps` 为 1–5，每次明确选择恰好 3 个 GPU UUID。
- 仅服务器控制面做最终调度和权限判定；前端设备快照只作为提示。
- 浏览器及仓库不保存 Runner 凭据、SSH 密钥和服务器密码。
- 普通用户只查看和操作自己任务；`train:job:manage` 控制提交、取消、停止。
- 真实 H20 训练必须在空闲或已获授权的共享设备上执行，核对任务最终状态、进程及租约。

## 文件结构

| 区域 | 文件 | 职责 |
|---|---|---|
| 开发连接 | `frontend/vite.config.ts`, `frontend/.env.example`, `README.md` | 代理目标及启动说明 |
| 真实总览 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/overview/{TrainOverviewService,TrainOverview}.java`，对应测试 | 从任务、Runner 状态得到真实值 |
| Runner/任务查询 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/{runner/mapper/RunnerMapper,job/mapper/JobMapper}.java`，对应 XML | 有界查询和在线判定 |
| 取消 | `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/job/{service/JobService,web/JobAdminController}.java` | 状态条件更新与归属校验 |
| 前端数据 | `frontend/src/api/train/{jobs,overview,devices}.ts` | 严格 API 类型 |
| 前端视图 | `frontend/src/views/train/{overview,devices,jobs}/index.vue` | 状态、提交、监控、取消和停止 |
| 前端逻辑 | `frontend/src/views/train/jobs/{jobActions,jobLogStream}.ts` | 状态动作规则与 SSE 游标 |
| 验收 | `docs/verification/frontend-workflow.md` | 本地代理及服务器实测记录 |

## 跨任务接口契约

以下签名由对应任务实现，后续任务按此调用；状态冲突使用当前项目的异常映射返回非成功响应。

```java
// JobService / JobAdminController
JobRecord cancel(long jobId, Instant now);
// POST /train/jobs/{jobId}/cancel
// GET /train/jobs?limit=50&beforeJobId=123
```

```ts
type CreateTrainJobRequest = {
  profileId: 'docowl2-srfund'
  maxSteps: number
  requestKey: string
  allowedGpuUuids: string[]
}
type JobAction = 'cancel' | 'stop' | 'none'
type JobSelection = { selectable: boolean; reason: string | null }
```

关键断言应使用具体值，例如 `QUEUED → CANCELED` 后 `startCommandId === null`，状态竞争时数据库仍为 `ALLOCATING`；同一 `requestKey` 重试得到同一个 job ID；日志事件 `17,17,18` 只显示两条，重连请求发送 `Last-Event-ID: 18`。

---

### Task 1: 本机前端代理服务器控制面

**Files:** Modify `frontend/vite.config.ts`, `README.md`; Create `frontend/.env.example`；Test `frontend/vite/vite.config.test.ts`（或直接运行 Vite 代理请求）。

**Interfaces:** `GPUTRAIN_CONTROL_PLANE_URL` 为仅供 Vite 读取的 origin；浏览器 API 路径仍为 `/dev-api`。

- [ ] **Step 1: 写代理配置测试。** 断言未配置时目标为 `http://localhost:8080`，配置 HTTPS origin 时 `/dev-api` 与 springdoc 代理都指向该 origin；拒绝含用户信息、路径、查询或非 HTTPS 非回环 HTTP 的地址。
- [ ] **Step 2: 运行测试确认失败。** `npm --prefix frontend test -- vite.config.test.ts`；预期新增用例失败。
- [ ] **Step 3: 实现配置。** 在 `vite.config.ts` 使用 `loadEnv(mode, process.cwd(), '')` 读取 `GPUTRAIN_CONTROL_PLANE_URL`；将现有常量 `baseUrl` 换成解析函数，默认回环地址，校验 URL 的 protocol、hostname、username、password、pathname、search、hash。保持 `/dev-api` rewrite 和 `VITE_APP_BASE_API` 不变。
- [ ] **Step 4: 写说明。** `.env.example` 只说明变量与非秘密 HTTPS origin 示例；README 写明复制到被 Git 忽略的 `.env.local`，切换目标需重启 Vite 并重新登录，服务器应先部署后端和 Runner；正式反向代理继续承载 `/prod-api`。
- [ ] **Step 5: 验证并提交。** `npm --prefix frontend test -- vite.config.test.ts`、`npm --prefix frontend run build:prod`、`git diff --check`；提交代理和说明。

### Task 2: 总览与 Runner 连接状态真实化

**Files:** Modify `TrainOverviewService.java`, `TrainOverview.java`, `RunnerMapper.java`, Runner XML、`JobMapper.java`, Job XML、`TrainOverviewServiceTest.java`, `TrainOverviewControllerTest.java`, `frontend/src/api/train/overview.ts`, `frontend/src/views/train/overview/index.vue`。

**Interfaces:** 保持 `GET /train/overview` 和既有 `status/activeExperiments/onlineRunners` 字段；增加 `sampledAt` 可选字段。`READY` 表示依赖查询成功；查询失败按现有 API 错误机制返回失败，而不是伪造 0。

- [ ] **Step 1: 先写服务测试。** 用可控 Mapper/Redis fake 或 mock 注入 2 个运行任务、1 个 90 秒内在线 Runner 和 1 个过期 Runner，断言返回 `READY/2/1`；模拟 Redis 失败，断言不返回 `READY/0/0`。
- [ ] **Step 2: 运行失败测试。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=TrainOverviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期因构造和查询接口缺失失败。
- [ ] **Step 3: 实现最小查询。** 任务 Mapper 增加 `countActive`，只计 `ALLOCATING/ALLOCATED/STARTING/RUNNING/STOPPING`；Runner Mapper 增加按 owner/状态读取 `runner_id,last_seen_at` 的有界投影，并用 Redis 在线 TTL key 判定。按当前用户可见范围计算计数；总览 Controller 将当前 userId/admin 标志传给 Service，避免泄露其他用户信息。测试覆盖普通用户与管理员范围。
- [ ] **Step 4: 前端显示真实状态。** 去掉静态含义的“运行正常”表述；失败时不保留误导性的旧 `READY`，显示断连和上次成功时间。对 `0` 明确说明“当前无在线 Runner/运行任务”。
- [ ] **Step 5: 验证并提交。** 运行相关 JUnit、`npm --prefix frontend test`、`git diff --check`；提交。

### Task 3: 后端取消与有界任务列表

**Files:** Modify `JobService.java`, `JobAdminController.java`, `JobMapper.java`, Job XML、`JobServiceTest.java`, `JobAdminControllerTest.java`。

**Interfaces:** `POST /train/jobs/{jobId}/cancel` 返回 `AjaxResult<JobRecord>`；`GET /train/jobs?limit=50&beforeJobId=...` 返回有界列表。`limit` 范围 1–100，按 `job_id DESC` 排序；无游标时取最新页。

- [ ] **Step 1: 写取消测试。** `PENDING/QUEUED` 无 START 命令时通过 `compareAndSetStatus` 更新为 `CANCELED`；重复取消返回终态；状态被调度器抢先改为 `ALLOCATING` 时返回冲突并保持原状态；非 owner 和无 manage 权限被拒绝。
- [ ] **Step 2: 写列表测试。** 断言普通用户只见本人、管理员范围按现有策略、最多 100 条、游标翻页无重复。运行相关 JUnit，预期新增用例失败。
- [ ] **Step 3: 实现接口。** 在 Controller 中先 `getOwned`，再调用 `JobService.cancel(jobId, Instant.now())`；Service 使用现有状态机及 Mapper 条件更新，并在并发冲突时返回明确冲突。列表 Mapper 添加 `job_id < beforeJobId` 条件和 `LIMIT`；保留现有 `GET /train/jobs` 默认 50 条的兼容路径。
- [ ] **Step 4: 验证并提交。** `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=JobServiceTest,JobAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`；检查 SQL XML 与 `git diff --check`，提交。

### Task 4: 提交表单显式选择 GPU

**Files:** Modify `frontend/src/api/train/jobs.ts`, `jobs.test.ts`, `frontend/src/views/train/jobs/index.vue`; Create `frontend/src/views/train/jobs/jobSelection.ts`, `jobSelection.test.ts`。

**Interfaces:** `CreateTrainJobRequest = { profileId: 'docowl2-srfund'; maxSteps: number; requestKey: string; allowedGpuUuids: string[] }`。UUID 必须与设备列表匹配；最终后端仍校验恰好三张卡。

- [ ] **Step 1: 写选择规则测试。** 恰好 3 个不同 UUID 才允许提交；快照缺失/过期、MIG、身份不明、活跃租约、显存不足时显示原因并禁用对应项。外部占用只作醒目提示，后端共享 profile 规则仍决定能否调度。断言提交 payload 含三个 UUID 和固定 profile/步数。
- [ ] **Step 2: 运行失败测试。** `npm --prefix frontend test -- jobSelection.test.ts jobs.test.ts`。
- [ ] **Step 3: 实现可测试的纯选择规则。** 在 `jobSelection.ts` 计算 `availableMemoryMiB = totalMemoryMiB - usedMemoryMiB`；沿用后端 70,000 MiB 门槛及 30 秒快照新鲜度；返回 `{ selectable, reason }`，不把前端结果当调度承诺。更新 API 类型和过时的旧 payload 测试。
- [ ] **Step 4: 接入表单。** 打开表单时并行刷新设备，显示名称、UUID 尾段、显存、采样时间、租约/外部占用；限制三选。只有 `train:job:manage` 显示“新建任务”。提交期间锁定按钮；失败时保留同一个 `requestKey` 和选择，成功后跳转或打开新任务监控。
- [ ] **Step 5: 验证并提交。** `npm --prefix frontend test`、`npm --prefix frontend run build:prod`、`git diff --check`；提交。

### Task 5: 任务列表、取消与停止操作

**Files:** Modify `frontend/src/api/train/jobs.ts`, `jobs.test.ts`, `frontend/src/views/train/jobs/index.vue`; Create `frontend/src/views/train/jobs/jobActions.ts`, `jobActions.test.ts`。

**Interfaces:** `cancelTrainJob(jobId)` 调用 `POST /train/jobs/{jobId}/cancel`；`listTrainJobs(limit, beforeJobId?)` 使用 Task 3 的参数。`jobActions(job)` 返回 `cancel | stop | none` 及中文状态。

- [ ] **Step 1: 写动作测试。** `PENDING/QUEUED` 为取消；`ALLOCATING` 暂无操作；`STARTING/RUNNING/QUARANTINED` 只有存在 `startCommandId` 才可停止；`STOPPING` 与终结态无动作。测试无 manage 权限时不显示按钮，测试请求路径和列表游标。
- [ ] **Step 2: 运行失败测试。** `npm --prefix frontend test -- jobActions.test.ts jobs.test.ts`。
- [ ] **Step 3: 实现状态规则和 API。** 将英文状态映射为中文标签；列表显示更新时间和数据同步时间，每 3 秒刷新当前页，离页清理计时器。底部“加载更多”使用 `beforeJobId`；不要无限拉取全部任务。
- [ ] **Step 4: 接入确认与冲突反馈。** 取消/停止前弹窗显示任务 ID 和动作；提交中禁用同一行；成功后读取单任务并刷新列表；状态冲突时展示当前状态并刷新，不显示“已停止”直至状态变为 `STOPPED`。显示后端错误文本。
- [ ] **Step 5: 验证并提交。** `npm --prefix frontend test`、`npm --prefix frontend run build:prod`、`git diff --check`；提交。

### Task 6: 监控详情和日志流可靠性

**Files:** Modify `frontend/src/views/train/jobs/index.vue`; Create `frontend/src/views/train/jobs/jobLogStream.ts`, `jobLogStream.test.ts`, `jobMonitor.test.ts`。

**Interfaces:** 日志客户端持有 `lastEventId`，初始 HTTP 历史与 SSE 共用去重逻辑；`dispose()` 取消 fetch、定时器和当前任务更新。

- [ ] **Step 1: 写流处理测试。** 分段 SSE frame、CRLF、重复 event ID、重连 `Last-Event-ID`、切换任务后迟到响应都不得重复或串入日志；日志历史超过 500 条时继续按游标拉取，直到不足一页。
- [ ] **Step 2: 运行失败测试。** `npm --prefix frontend test -- jobLogStream.test.ts jobMonitor.test.ts`。
- [ ] **Step 3: 提取流逻辑。** 将现有 `streamLogs` 的解码、frame 解析、重连和游标放入 `jobLogStream.ts`；详情只管理生命周期。重连带有限退避，401/403 显示错误并停止重试；关闭或切换立即 abort。
- [ ] **Step 4: 修正监控状态。** `Promise.all` 中一项失败时分别标出日志、指标、告警或资源失败，不清空已有成功数据；空列表展示采集尚未开始；详情状态每 3 秒刷新，终结态停止无意义的轮询。格式化无效时间和空数值。
- [ ] **Step 5: 验证并提交。** `npm --prefix frontend test`、`npm --prefix frontend run build:prod`、`git diff --check`；提交。

### Task 7: 类型检查与完整回归门槛

**Files:** Modify `frontend/src/api/train/jobs.test.ts`, `frontend/src/views/train/jobs/index.vue`；如仍有现存全局类型错误，按 `vue-tsc` 结果最小修改 `frontend/src/types`、`frontend/src/store/modules/user.ts`、`frontend/src/views/lock.vue` 等确切文件。

**Interfaces:** `npm --prefix frontend run typecheck` 执行 `vue-tsc --noEmit`；生产构建不代替类型检查。

- [ ] **Step 1: 添加 `typecheck` npm script 并运行。** 保存当前诊断：Vue `App/createApp`、`pwdChrtype`、lock 页隐式 any、GPUTrain 旧 payload/隐式 any。逐个定位声明来源，不通过关闭 `strict` 或全局 `any` 消错。
- [ ] **Step 2: 修复 GPUTrain 类型错误。** 更新旧 API 测试载荷与 `JobLogEvent`/响应类型；把回调参数显式化。
- [ ] **Step 3: 修复阻断全局 gate 的现存类型错误。** 对 Vue 声明与依赖版本做最小兼容修复；为 `UserInfoResult` 补实际后端字段或移除不存在的访问；为 lock 页参数指定真实类型。每次运行 `npm --prefix frontend run typecheck`，期望最终退出码 0。
- [ ] **Step 4: 全量验证并提交。** `npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build:prod`、`mvn -f backend/pom.xml test`、`git diff --check`；保存通过摘要并提交。

### Task 8: 本机页面连接服务器的实测

**Files:** Create `docs/verification/frontend-workflow.md`; Modify `README.md`（仅实测发现的启动说明缺口）。

**Interfaces:** 使用真实服务器 HTTPS origin 作为本机 Vite 代理目标；测试账号拥有相应菜单与 `train:job:manage` 权限。

- [ ] **Step 1: 预检。** 记录控制面版本、Runner 在线状态、三个测试 GPU 的 UUID/显存/外部进程、现有任务和租约。确认测试账号、测试窗口及 GPU 使用授权；不打印令牌和密钥。
- [ ] **Step 2: 浏览器走查。** 本机启动 Vite，登录后检查工作台、设备列表、任务列表和权限受限账号；验证后端断连与重新连接提示。
- [ ] **Step 3: 受控训练。** 选择三张获授权设备并提交 1-step 任务；记录 job ID、状态变化、日志游标、指标/资源/告警。另用受控长任务测试停止确认与最终 `STOPPED`、进程退出和租约释放；若无法安全制造排队态，用自动化测试验证取消，不强行占用共享 GPU。
- [ ] **Step 4: 记录与收尾。** 在验收文档写日期、环境、命令、结果及未通过项；移除测试产生的临时凭据文件，核对没有遗留运行任务或租约。`git diff --check` 后提交记录。

## Plan self-review

- 设计范围对应 Task 1–8：拓扑、真实状态、显式选卡、状态动作、监控、权限、类型与实测均有验证。
- 后端控制面与前端 UI 的接口按任务顺序交付；Task 4/5 不依赖尚未实现的页面运行时服务器切换。
- 真实训练前必须先完成预检；取消功能的服务级并发测试不需要人为制造共享服务器负载。
