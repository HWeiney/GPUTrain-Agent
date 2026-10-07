# 前端提交、监控、停止闭环验收

日期：2026-10-07。分支：`feat/frontend-workflow`。

## 当前本机环境

- 本机 Vite 页面：`http://localhost:80/`；本机控制面：`http://localhost:8080/`。`frontend/.env.local` 未配置远程 origin，当前 `/dev-api` 代理指向本机控制面。
- 使用已有测试账号在页面登录，未在本文档记录账号、密码或令牌。
- 工作台显示在线 Runner **0**、运行中实验 **0**；GPU 设备页显示“暂无已上报设备”；训练任务页显示 **0** 条任务。

## 页面走查

| 检查点 | 结果 |
|---|---|
| 登录后 GPUTrain 菜单及三个页面 | 训练工作台、GPU 设备、训练任务均可打开，工作台读取到控制面数据 |
| 新建任务 | 固定 `DocOwl2-SRFUND`、1–5 步、恰好三张 GPU 的表单显示正常 |
| 无可用 GPU | 显示 Runner 提示，提交按钮禁用；未创建任务 |
| 窄屏弹窗 | 约 434 px 视口下，表单及取消、提交按钮完整可见 |
| 本机代理 | 未登录时 `/dev-api/train/overview` 返回认证失败响应，说明代理到达控制面；登录后工作台显示同步结果 |

本机没有在线 Runner 或 GPU 快照，因此本次不能安全执行真实任务提交、日志流、停止及租约释放验收。当前运行的 Java 服务也未在本次代码修改后重启，不能把浏览器走查当作新后端接口的运行时验证。

当前 `JobService.submit()` 在一次事务中同步分配资源并进入启动流程；资源不足时提交失败，不会持久化一个排队任务。因此“取消待处理/排队任务”接口及并发测试已具备，但普通提交路径暂时不会自然产生可供取消的排队任务。若产品需要真正的等待队列，后续须单独设计持久化排队与调度重试。

## 自动化回归

- `npm --prefix frontend test`：7 个文件，31 个测试通过。
- `npm --prefix frontend run typecheck`：通过。
- `npm --prefix frontend run build:prod`：通过。
- `mvn -f backend/pom.xml test -q`：通过。Runner 测试需要本地 socket/进程访问，故在允许该访问的执行环境运行。
- `git diff --check`：通过。

## 服务器实测待办

1. 在服务器部署本分支对应的控制面和 Runner，并按 README 将本机 `frontend/.env.local` 的 `GPUTRAIN_CONTROL_PLANE_URL` 指向服务器 HTTPS origin；重启 Vite、重新登录。
2. 核对测试账号权限、Runner 在线、三个获授权 GPU 的 UUID、显存、外部进程、现有任务及租约。
3. 用 1-step 任务验证提交、状态变化、日志续传、指标/资源/告警；记录 job ID 和最终状态。
4. 在获授权的受控任务上验证停止后的进程退出与租约释放。排队取消已有服务级并发测试，无需刻意制造共享 GPU 负载。
