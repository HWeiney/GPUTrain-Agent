# Phase 7 Verification: Deployment, Evaluation, and Project Evidence

Date: 2026-10-07. Worktree: `feature/phase-4-observability`.

## Local release checks

| Check | Result |
|---|---|
| `mvn -pl ruoyi-admin,ruoyi-train-runner -am package` from `backend/` with local loopback enabled | **BUILD SUCCESS**; 278 tests across reactor reports, 0 failures/errors/skips; admin and Runner packages produced |
| Follow-up `mvn -f backend/pom.xml -pl ruoyi-admin -am -Dtest=QwenCompatibleChatClientTest -Dsurefire.failIfNoSpecifiedTests=false package` | **BUILD SUCCESS**; Spring context regression test proves the Qwen client can be constructed |
| Follow-up `mvn -f backend/pom.xml -pl ruoyi-admin -am -Dtest=McpPermissionAuthorizationManagerTest -Dsurefire.failIfNoSpecifiedTests=false package` | **BUILD SUCCESS**; exact job-view/admin permission and no-permission cases pass |
| `npm test -- --run` from `frontend/` | **PASS**; 8 tests across 3 files |
| `npm run build:prod` from `frontend/` | **PASS**; Vite production build completed |
| `docker compose --env-file .env.example -f deploy/compose.yaml config -q` | **PASS** |
| `bash -n scripts/demo-phase7-mcp.sh`; `git diff --check` | **PASS** |

The first sandboxed Maven attempt could not bind loopback ports. The full package then exposed stale Runner HTTP fixtures; `RunnerApplicationTest` now supplies the Phase 4 log-offset endpoints and empty managed-log fixtures. During remote rollout, Spring startup revealed that `QwenCompatibleChatClient` had multiple constructors and no explicit injection constructor. A regression test reproduced the exact `No default constructor found` failure; adding `@Autowired` to the production constructor made the test and package pass. The first authenticated MCP request then exposed a web-expression bean-resolver error in the servlet permission rule. `McpPermissionAuthorizationManager` now checks the authenticated `LoginUser` permission set directly; targeted tests and live positive/negative requests pass.

## Target deployment and recovery evidence

Target: `hanw@10.31.118.177`; services run as user systemd units. On 2026-10-07, preflight found eight NVIDIA H20-3e devices, all with high utilization, but GPUs 0, 1, and 5 had 107,676, 107,642, and 131,158 MiB free respectively. Other users' processes remained untouched. `/data` had 50 GiB free (95% used).

- Created `/data/hanw/hw_code/gputrain-test/phase7-backup/20261007T011132Z/` with pre-upgrade and final control-plane JARs, pre-upgrade, post-migration and post-acceptance database dumps, `.env`, Runner properties, both user service units, and SHA-256 manifest. Secret-bearing files and backup directory are mode `0600`/`0700`; every listed hash verifies. The pre-upgrade dump is retained as the rollback point.
- Applied the additive `backend/sql/gputrain_phase6.sql` migration. Deployed the rebuilt Spring Boot control plane; Runner was not restarted. An initial JAR failed because Spring could not choose the Qwen client's constructor. The service was restored from the pre-upgrade JAR and returned HTTP 200 health before the fixed build was deployed. A second fix corrected the live MCP permission boundary. The final control plane and existing Runner are both active, with no automatic restart loop after the final publication.
- A post-migration dump and the final post-acceptance dump were each restored into isolated temporary databases and compared before cleanup. The final snapshot matched at 45 tables, 4 jobs, 12 lease rows, 12 log chunks, 521 metrics, 0 alerts, and 9 MCP audit rows; deleted temporary user IDs were absent. **Restore rehearsal passed.**
- Anonymous `POST /mcp` initialization returns the RuoYi application envelope `code=401`. Two short-lived users were created with only job-view/manage (manager) and job-view (observer) permissions. Authenticated Streamable HTTP initialization passed; `tools/list` returned exactly the six read-only tools. The manager read its owned job summary, logs, metrics, alerts, and resources; the summary contained only the six safe fields. The observer's list was isolated, and each user was denied when requesting the other's job. Observer job creation returned application `code=403`; after removing its view permission and refreshing its token, MCP initialization was rejected (`code=401`). **Nine audit rows persisted** (7 successes, 2 cross-owner denials). Both accounts/roles were deleted, JWTs revoked, and Runner 1 owner restored to user 1.

## Real GPU acceptance runs

On the same target, a bounded 1-step DocOwl2-SRFUND run used physical GPUs 0, 1, and 5 (`GPU-3ab7105f-3894-f318-fab6-eddd01366b7c`, `GPU-26e203e9-5bf1-094b-8d1a-2525b5da7c7e`, and `GPU-0f926d66-3a82-3a68-2f99-9882870d0d5b`). Each met the 70,000 MiB free-memory threshold before launch. High utilization did not prevent the run; the training process was allowed to share the devices without stopping other users' work.

- Run ID: `gputrain-phase7-gpu015-20261007`; process exit code **0**; `global_step/max_steps=1/1`; `train_loss=3.98971343`; `train_runtime=26.0541s`; `checkpoint-1` written.
- Log: `/data/hanw/hw_code/gputrain-test/logs/gputrain-phase7-gpu015-20261007.log` (44,090 bytes, mode `0600`). GPU memory returned to the pre-run sampled values after process exit. Control-plane and Runner services remained active.

A second run exercised the deployed GPUTrain API and Runner end to end:

- Managed job `9`, request key `phase7-managed-20261007-p7a`, succeeded on the same three allowlisted GPU UUIDs. The training step reported `train_loss=3.98971343` and `train_runtime=26.9208s`; the job's final status is `SUCCEEDED`.
- The control plane persisted 3 log chunks with stdout cursor offset 44,140, 132 training metrics, 12 GPU resource samples, and 0 alerts. All 3 job leases are `RELEASED`; all 12 historical leases are released. The job's training log remained queryable through MCP after completion.
- Job 9 was submitted by the temporary manager account; the account and role were removed after testing, while job and audit evidence remain. The active Runner owner is restored to user 1.

## Release materials

- `docs/operations/deployment-and-recovery.md`: setup, backup, migration, rollout, rollback, restore and Runner-safe shutdown.
- `scripts/demo-phase7-mcp.sh`: read-only overview, MCP initialization, tool listing and current-user experiment listing; it requires a valid JWT and stores its temporary curl config as mode `0600`.
- `docs/project-summary/resume-copy.md`: Chinese and English summaries with evidence and scope limits.

## Scope limits and operational notes

- The live MCP smoke used loopback HTTP because the control plane listens on `127.0.0.1:8080`; reverse-proxy HTTPS forwarding and an IDE-specific client configuration were not exercised. The read-only demo script passed syntax checks, and equivalent authenticated MCP calls were executed directly.
- `/data` remains at 95% use with about 49 GiB free. Do not delete logs, checkpoints, model cache, or user data to create space. The release and verified backups fit in available capacity; schedule a separate retention window before any long benchmark.

Phase 7 functional acceptance is **complete**. The shared-GPU one-step runs verify job submission, execution, persistence, query isolation, and cleanup; they are functionality checks, not throughput benchmarks or model-quality evaluations.
