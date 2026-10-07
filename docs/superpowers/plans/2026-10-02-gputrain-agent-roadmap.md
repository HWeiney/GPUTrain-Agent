# GPUTrain Agent Implementation Roadmap

## Purpose

The approved design is split into seven implementation plans so that every plan ends with working, reviewable software. Later plans are written only after the preceding phase has landed, allowing exact paths and interfaces to follow the actual codebase rather than assumptions.

| Phase | Plan deliverable | Exit gate | Status (2026-10-07) |
|---|---|---|---|
| 1 | RuoYi foundation and GPUTrain skeleton | Login works, GPUTrain menu is permission-controlled, backend/frontend builds pass | Done |
| 2 | User-level Training Runner | Runner registers, heartbeats, long-polls, executes a safe fixture process, and recovers it | Code done; fixture acceptance passed on the Linux target; no pre-deploy outside-directory baseline |
| 3 | GPU inventory, leases, and job state machine | Concurrent multi-GPU allocation is atomic; shared-GPU use requires a fresh snapshot and per-card free-memory threshold; external processes are never interrupted | **Complete**; Linux H20 real discovery and 3-GPU, 1-step DocOwl2-SRFUND run succeeded (`jobId=5`, checkpoint written, this job's leases released). Later safe re-probe confirmed job4's process was absent; job4 was marked `STOPPED` with audit records and its three leases released before phase4 job8. MySQL-specific tests were not rerun after final changes |
| 4 | Logs, metrics, and alerts | SSE resumes by event ID; Runner upload resumes by stream byte offset; metric/GPU history renders; deterministic alerts fire | Implementation deployed; managed GPU 4/5/6 job8 succeeded (1/1 step, loss 3.98971343); 4 contiguous log chunks persisted through byte 44,327, 133 metrics and per-GPU resource history persisted; Runner owner and all leases restored/clean. Alert-trigger fixtures pass locally; live alert deduplication and browser SSE reconnect remain unverified. |
| 5 | Qwen multi-agent workflow | Planner, Diagnosis, and Execution agents produce validated structured outputs behind policy gates | **Complete**; 207 tests pass, Qwen 3.8 live role checks and JSON smoke pass, approval-only Execution draft validated; see `docs/verification/phase-5-qwen-workflow.md` |
| 6 | MCP and IDE integration | Scoped MCP clients can query their own experiments; read-only tools are allowlisted and audited | **Complete; target HTTP acceptance passed**; authenticated six-tool allowlist, no-view rejection, bidirectional owner isolation, 9 persisted audit rows, and temporary permission cleanup verified; external reverse-proxy/IDE client setup remains outside this target smoke |
| 7 | Deployment, evaluation, and resume evidence | Deployment, backup/restore, real-GPU acceptance, demo script, measured results, and resume copy | **Functionally accepted**; control plane/Runner active; checksummed pre/post backups and isolated restore passed; managed job9 succeeded (`1/1`, loss `3.98971343`, 26.9208s step runtime) with 3 logs, 132 metrics, 12 resource samples, released leases, and persisted audit evidence; external HTTPS/IDE smoke and long-run benchmark remain out of scope |

Each phase updates `docs/interview/项目面试考点.md` with questions whose answers cite implemented classes and tests.

## Planning rule

Before starting a phase, create `docs/superpowers/plans/YYYY-MM-DD-gputrain-agent-phase-N-<name>.md`. The plan must use the interfaces produced by the previous phase, contain failing-test-first steps, exact verification commands, and a documentation checkpoint.
