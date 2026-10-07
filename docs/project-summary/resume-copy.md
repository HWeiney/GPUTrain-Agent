# GPUTrain Agent — Resume Project Copy

## 中文简历版

**GPUTrain Agent｜多用户 GPU 训练管理与智能诊断平台**

- 基于 Spring Boot/RuoYi 构建模块化训练控制面与 Linux 用户级 Runner，实现用户隔离的任务状态机、基于 GPU UUID 的多卡原子租约、Runner 心跳/进程对账及安全停止边界。
- 实现 stdout/stderr 字节 offset 断点续传、训练指标与 GPU 利用率/显存历史、CUDA OOM/NaN/NCCL 可验证告警；前端提供任务运行状态和监控视图。
- 设计 Planner、Diagnosis、Execution 的 JSON 契约和策略门；Execution 仅生成需人工确认的训练草案。为 IDE/MCP 增加 JWT 权限保护的 Streamable HTTP 只读接口、owner 数据隔离、六工具 allowlist 和元数据审计。
- 在 8×NVIDIA H20 目标机完成过三卡 1-step DocOwl2-SRFUND 托管验收：job8 成功，耗时约 41.24 秒，`train_loss=3.98971343`；日志游标连续至 44,327 字节，记录 133 条训练指标及每卡 6 个资源采样。该结果为短时功能验收，不代表长训练吞吐或模型质量评估。
- 阶段七再次在 H20 共享负载下用 GPU 0/1/5 完成固定脚本 1-step 实测：退出码 0、耗时 26.05 秒、loss `3.98971343`、写出 `checkpoint-1`；另完成迁移前后数据库备份和隔离库恢复核对（45 张表、3 个任务、9 条租约一致）。该新实测为直接脚本短测，不是 GPUTrain 托管任务。
- 随后通过部署后的 GPUTrain 控制面和 Runner 完成托管 job9：`SUCCEEDED`，1 step、step runtime 26.92 秒，落库 3 个日志块、132 条指标、12 个 GPU 资源样本，租约全部释放；两名临时用户的 MCP owner 隔离和 9 条调用审计记录通过实测。

## English resume version

**GPUTrain Agent | Multi-user GPU training control and diagnosis platform**

- Built a modular Spring Boot control plane and per-user Linux runner with owner-scoped job state transitions, UUID-based atomic multi-GPU leases, heartbeat/process reconciliation, and guarded stop semantics.
- Added resumable stdout/stderr ingestion by byte offset, training-metric and GPU utilization/memory history, deterministic CUDA OOM/NaN/NCCL alerts, and a job-monitoring UI.
- Defined structured Planner/Diagnosis/Execution contracts with policy checks and human approval for execution drafts; exposed six read-only MCP tools over JWT-protected Streamable HTTP with owner isolation and metadata-only audit.
- Completed a short managed acceptance run on three NVIDIA H20 GPUs: one DocOwl2-SRFUND training step succeeded in ~41.24 s (`train_loss=3.98971343`), with 44,327 contiguous log bytes, 133 metrics, and six resource samples per GPU. This was a functional smoke run, not a throughput or model-quality benchmark.
- Repeated the fixed one-step training script on shared H20 GPUs 0/1/5 during Phase 7: exit code 0, 26.05 s, `train_loss=3.98971343`, and `checkpoint-1` written. Verified pre/post-migration database backups and an isolated restore (45 tables, 3 jobs, 9 lease rows). This follow-up was a direct script run, not a managed GPUTrain job.
- Completed managed job9 through the deployed GPUTrain control plane and Runner: `SUCCEEDED`, one step, 26.92 s step runtime, 3 persisted log chunks, 132 metrics, 12 GPU resource samples, and all leases released; two-user MCP owner isolation and 9 metadata audit records passed.

Evidence: `docs/verification/phase-3-gpu-scheduling.md`, `docs/verification/phase-4-observability.md`, and `docs/verification/phase-6-mcp.md`. Stage 7 deployed release/MCP HTTP smoke and restore rehearsal remain pending a safe target maintenance window.
