# GPUTrain Agent Phase 4 Observability Implementation Plan

> **For agentic workers:** Execute inline in this task using the approved design and verify each slice before proceeding.

**Goal:** Add resumable job logs, persisted training metrics and deterministic alerts to the existing Phase 3 job workflow.

**Architecture:** Extend the Runner protocol to upload bounded log chunks with byte offsets. The control plane persists chunks idempotently, authorizes access through the owning job, serves history and SSE replay, parses common training scalars, and evaluates fixed rules without an external service. The existing job list/detail page will show status, metrics, logs and alerts.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis, MySQL 8, Vue 3, TypeScript, Vitest.

## Global Constraints

- Keep full execution and GPU allocation behavior from Phase 3 unchanged.
- Use Runner HMAC authentication for ingestion and the logged-in job owner/admin for reads.
- Bound upload chunk size and log query windows; offsets count UTF-8 bytes per command and stream.
- Do not introduce Kafka, Elasticsearch, Prometheus, or other services in this phase.
- Alerts are deterministic, stored, deduplicated by job/rule, and do not trigger model calls or actions.

## File Map

- Protocol and Runner: `backend/ruoyi-train-runner-protocol/...`, `backend/ruoyi-train-runner/...`
- Control plane: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/observability/`, `.../runner/web/RunnerApiController.java`
- Persistence/migration: `backend/ruoyi-train-core/src/main/resources/mapper/train/`, `backend/sql/gputrain_phase4.sql`
- UI: `frontend/src/api/train/`, `frontend/src/views/train/jobs/`
- Evidence: roadmap, README, interview notes, `docs/verification/phase-4-observability.md`

## Tasks

### Task 1: Protocol and Runner log tailing

- Add validated `LogChunk`, stream type, and acknowledgement protocol types.
- Add secure bounded byte-range reads for managed stdout/stderr files and resume from the server-acknowledged offset after retries/restarts.
- Test independent stream offsets, partial UTF-8 boundary handling, empty files, truncation/rotation, bounded chunks, and Runner restart recovery before implementing.

### Task 2: Durable ingestion, replay, and authorization

- Add SQL tables for log chunks/cursors, metrics, and alerts with unique `(command, stream, offset)` ingestion and job indexes.
- Add MyBatis mappers/services and authenticated Runner endpoints; reject mismatched Runner/command and non-monotonic gaps while treating exact retries idempotently.
- Add owner/admin job log history and SSE endpoint using `Last-Event-ID`; enforce a bounded replay window and per-job ownership on every request.
- Test idempotent retry, offset gap/conflict, permission denial, history paging, and SSE replay.

### Task 3: Metrics and deterministic alert rules

- Parse bounded log lines for `global_step`, `max_steps`, `train_loss`, NaN/Inf, CUDA OOM, NCCL errors, and abnormal terminal state.
- Persist metric samples and deduplicated alerts; expose current status, resource snapshot, metric history, and alert history through job-scoped APIs.
- Test parser noise/invalid values, threshold transitions, alert deduplication, and job isolation.

### Task 4: Job detail UI

- Extend the job detail page with status and GPU summary, metric history, resumable streaming logs, alert list, and historical job records using existing APIs and component conventions.
- Add focused Vitest coverage for API requests, log cursor resume, metric/alert rendering, and empty/error states.

### Task 5: Phase evidence and verification

- Add migration/upgrade instructions and deterministic API acceptance evidence.
- Update roadmap, README, and interview notes with implemented class/test references; distinguish automated acceptance from target-GPU acceptance.
- Run targeted backend/frontend tests, production build, migration syntax checks, and `git diff --check`.

## Exit Gate

Runner retries must not duplicate or skip bytes; a browser reconnecting with its last event ID must replay subsequent logs; only the job owner/admin can read logs, metrics, or alerts; metric samples/history render; deterministic error rules create one alert per job/rule; existing Phase 3 tests remain green.
