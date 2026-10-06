# GPUTrain Agent Implementation Roadmap

## Purpose

The approved design is split into seven implementation plans so that every plan ends with working, reviewable software. Later plans are written only after the preceding phase has landed, allowing exact paths and interfaces to follow the actual codebase rather than assumptions.

| Phase | Plan deliverable | Exit gate | Status (2026-10-06) |
|---|---|---|---|
| 1 | RuoYi foundation and GPUTrain skeleton | Login works, GPUTrain menu is permission-controlled, backend/frontend builds pass | Done |
| 2 | User-level Training Runner | Runner registers, heartbeats, long-polls, executes a safe fixture process, and recovers it | Code done; fixture acceptance passed on the Linux target; no pre-deploy outside-directory baseline |
| 3 | GPU inventory, leases, and job state machine | Concurrent multi-GPU allocation is atomic; shared-GPU use requires a fresh snapshot and per-card free-memory threshold; external processes are never interrupted | **Complete**; Linux H20 real discovery and 3-GPU, 1-step DocOwl2-SRFUND run succeeded (`jobId=5`, checkpoint written, this job's leases released). `jobId=4` remains quarantined after its validation run was stopped during Runner restart; its leases are intentionally retained pending safe re-probe. MySQL-specific tests were not rerun after final changes |
| 4 | Logs, metrics, alerts, and checkpoints | SSE resumes by offset, metrics render, and deterministic alerts fire | Not started |
| 5 | Qwen multi-agent workflow | Planner, Diagnosis, and Execution agents produce validated structured outputs behind policy gates | Not started |
| 6 | MCP and IDE integration | Scoped MCP clients can query their own experiments; dynamic tools are allowlisted and audited | Not started |
| 7 | Deployment, evaluation, and resume evidence | Docker Compose deployment, real-GPU acceptance suite, demo script, measured results, and resume copy | Not started |

Each phase updates `docs/interview/项目面试考点.md` with questions whose answers cite implemented classes and tests.

## Planning rule

Before starting a phase, create `docs/superpowers/plans/YYYY-MM-DD-gputrain-agent-phase-N-<name>.md`. The plan must use the interfaces produced by the previous phase, contain failing-test-first steps, exact verification commands, and a documentation checkpoint.
