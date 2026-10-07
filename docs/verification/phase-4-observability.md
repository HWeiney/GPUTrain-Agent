# 阶段四日志、指标与告警验收记录

日期：2026-10-06
分支：`feature/phase-4-observability`，基于阶段三 `c9b15a59`
范围：Runner 日志增量上报、持久化 offset、历史查询/SSE、训练指标、GPU 资源快照与确定性告警。

## 实现

- `RunnerLogChunk` 按命令与 stdout/stderr 流传送有界原始字节；Runner 每次从控制面读取已确认 offset，网络重试和 Runner 重启后从持久游标续传。`LogFileTailer` 限定允许目录、拒绝符号链接目标、按 UTF-8 行边界发送日志，单块最多 32 KiB。
- `JobObservabilityService` 在事务中检查 Runner 和 job 归属、连续 offset 与相同块重试；日志事件以数据库 ID 提供 `Last-Event-ID` 回放。job 日志、指标、告警和资源接口均在服务层检查 owner/admin 数据范围。
- 指标解析接受日志内有限数值字段；GPU 资源读取任务租约时间窗内每张 GPU 的最近采样。规则 `CUDA_OOM`、`NAN_LOSS`、`NCCL_ERROR` 确定性匹配并按 job/rule 唯一去重。
- 前端任务监控抽屉提供任务状态、Runner/GPU 数、租约期间 GPU 利用率/显存采样、指标历史、告警和从上次事件 ID 自动恢复的日志流。

## 本地验证

- 基线阶段三核心测试：161 项通过，0 失败。
- 阶段四后端 core reactor：`mvn -f backend/pom.xml -pl ruoyi-train-core -am test`，164 项通过，0 失败/错误/跳过；包含告警和 Hugging Face 指标格式测试。
- Runner 日志尾随与协议定向测试：`mvn -f backend/pom.xml -pl ruoyi-train-runner -am -Dtest=LogFileTailerTest,RunnerLogChunkTest -Dsurefire.failIfNoSpecifiedTests=false test`，5 项通过。
- Runner 既有 HTTP 集成测试尝试运行失败：沙箱禁止测试服务器绑定本机回环端口（`SocketException: Operation not permitted`）；不是阶段四测试断言失败，需在允许本机端口的环境运行完整 Runner 测试。
- 前端 `npm --prefix frontend test -- --run`：8 项通过；`npm --prefix frontend run build:prod`：生产构建成功。
- Compose 文件结构：`docker compose --env-file .env.example -f deploy/compose.yaml config -q`。

## Linux 目标机部署与实机短测（2026-10-06）

- 通过专用 SSH key 连接 `hanw@10.31.118.177`。部署前确认 GPUTrain 的数据库中只有终态任务（job 5 `SUCCEEDED`、job 4 `QUARANTINED`），没有活动 job；另有其他用户训练进程运行中。
- 部署前备份控制面 JAR、Runner JAR、Runner 示例配置和 MySQL `gputrain` 数据库。数据库备份位于目标机 `/data/hanw/hw_code/gputrain-test/phase4-backup/gputrain.20261006-194621.sql.gz`。
- 应用 `backend/sql/gputrain_phase4.sql`，确认 `train_log_cursor`、`train_job_log`、`train_job_metric`、`train_job_alert` 四表已创建；部署新控制面与 Runner JAR 并重启两个用户级服务。最终复核两个用户级服务均为 active；未认证访问 `/actuator/health` 返回 RuoYi `code=401`。
- 未登录访问日志、指标、告警和资源 API 均得到 RuoYi `code=401`；未认证 Runner 请求返回 `RUNNER_AUTH_INVALID`。因此确认了匿名访问被拒绝，但尚未用两个已登录用户验证 owner 隔离。
- 为在控制面限定 GPU 4/5/6，`CreateJobRequest` 增加可选 `allowedGpuUuids`，并只接受空列表或三个格式有效的 UUID；`JobAdminControllerTest` 的 8 项定向测试通过。首次提交被 job 4 的 quarantine leases 拒绝。按用户授权先核验 job 4 的 PID/进程均已不存在，备份数据库后将 job4 安全置为 `STOPPED`、释放 3 条租约并记审计；没有终止其他用户进程。随后的验收前状态为 0 个活动 job lease、0 个临时账号。
- 按用户指定物理 GPU 4、5、6，使用唯一 run id `gputrain-phase4-gpu456-20261006` 运行固定 DocOwl2-SRFUND 1-step 短测。启动前可用显存依次为 81,464 / 102,040 / 81,814 MiB；训练约 46 秒，`global_step/max_steps=1/1`、`train_loss=3.98971343`，`checkpoint-1` 写出，退出码 0。训练后显存回到训练前采样值，进程已退出。日志保存在目标机 `/data/hanw/hw_code/gputrain-test/logs/gputrain-phase4-gpu456-20261006.log`。
- 此直接短测没有创建 GPUTrain job。阶段四端到端证据由以下托管验收 job8 提供。

## GPUTrain 托管端到端验收（2026-10-06）

- 用户明确授权后，为验收创建仅含 `train:job:view`、`train:job:manage` 的短期账号，并短暂将 Runner 1 绑定到该账号。job8 仅允许 GPU UUID `GPU-4ef9d5e8-1f94-7df7-96e8-090e057e8264`、`GPU-0f926d66-3a82-3a68-2f99-9882870d0d5b`、`GPU-61d37364-7967-ee7d-a22c-8ff331497a19`，运行 1 step 后以 `SUCCEEDED` 结束；`train_loss=3.98971343`，runtime `41.2406s`，`checkpoint-1` 写出。任务日志位于目标机 `/data/hanw/hw_code/gputrain-test/logs/` 对应 Runner 上报的数据库记录中。
- 控制面持久化 4 个 stdout 日志块，字节区间连续为 `[0,19888)`、`[19888,40583)`、`[40583,41705)`、`[41705,44327)`；`train_log_cursor.next_offset=44327`。这验证本次训练日志上传和连续 offset 游标。数据库记录 133 条解析指标，其中 `train_loss=3.98971343`；任务租期内三张 GPU 各记录 6 个资源快照，utilization 峰值分别为 100%、100%、99%。该成功任务告警数为 0（没有人为注入错误日志）。
- 最终复核：job8 `SUCCEEDED`；3 条 GPU 租约均 `RELEASED`；Runner 1 owner 已恢复为用户 1；临时用户/角色已删除；活动 GPU 租约为 0。临时重绑定前的数据库备份保存在目标机 `/data/hanw/hw_code/gputrain-test/phase4-backup/gputrain.pre-managed-acceptance.20261006-203815.sql.gz`。
- 确定性规则 `CUDA_OOM`、`NAN_LOSS`、`NCCL_ERROR` 的匹配由本地 `AlertEvaluatorTest` 固定用例验证；本次正常训练没有触发告警，因此目标机告警去重未通过故障注入实测。目标机匿名访问拒绝已验证；两名不同登录用户的 owner 隔离以及 SSE `Last-Event-ID` 浏览器重连尚未作为独立实机验收项。GPU 4/5/6 训练期间有其他任务共享，1-step 结果不代表长训练性能或完整评估。

## 告警边界

当前规则只覆盖日志中明确出现的 CUDA/GPU OOM、NaN/Inf loss、NCCL error/timeout；没有声称已实现 GPU 空转、多卡不均衡、磁盘不足、温度或训练停滞告警。告警只记录证据，不自动停止任务。
