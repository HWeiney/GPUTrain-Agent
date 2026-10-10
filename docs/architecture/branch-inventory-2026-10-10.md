# 分支整理记录（2026-10-10）

## 保留的分支

| 分支 | 用途 | 整理时提交 |
| --- | --- | --- |
| `main` | 稳定集成线；只接收已评审和验收的开发成果 | `3030c85d` |
| `dev` | 日常开发线；由 `feat/frontend-workflow` 更名，保留已有 SSH 多服务器提交与当前工作区未提交代码 | 实现基线 `9cd8ff0c` |

`main` 是 `dev` 的祖先。两者目前只有一个实际工作目录，检出在 `dev`。工作区的代码修改仍未提交；整理分支没有更改这些代码内容。

## 历史分支的内容

| 已移除的本地分支 | 主要工作 | 合并关系 |
| --- | --- | --- |
| `feature/phase-2-runner` | 用户级 Runner 注册、心跳、进程恢复与 Linux fixture 验收 | 其提交已在 `main` 中 |
| `feature/phase-3-gpu` | GPU 设备快照、调度租约和固定训练任务 | 其提交已在 `main` 中 |
| `feature/phase-4-observability` | 日志/指标/告警，后续 Agent、MCP 与部署验收阶段 | 其提交已在 `main` 中 |
| `feat/frontend-workflow` | 页面工作流及多服务器 SSH 训练 | 原分支更名为 `dev`，提交没有丢失 |

三个阶段工作树已移除。Phase 2 未提交文档、`.superpowers` 工作记录及 Phase 4 被忽略的 `.env` 已在仓库外的 `../GPUTrain-branch-archive-2026-10-10/` 保存。该目录包含配置文件，应保持私有，不要加入 Git 或公开分享。构建产物、`node_modules` 等可再生成的缓存未归档。

分支包含关系只证明 Git 提交未丢失，不代表 SSH 新链路已完成实机验收。合并 `dev` 到 `main` 前，仍需处理当前未提交代码并完成 [设计评审](design-review-2026-10-10.md) 中的关键验收。
