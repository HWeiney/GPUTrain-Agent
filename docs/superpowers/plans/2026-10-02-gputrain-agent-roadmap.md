# GPUTrain Agent Implementation Roadmap

## Purpose

The approved design is split into seven implementation plans so that every plan ends with working, reviewable software. Later plans are written only after the preceding phase has landed, allowing exact paths and interfaces to follow the actual codebase rather than assumptions.

| Phase | Plan deliverable | Exit gate |
|---|---|---|
| 1 | RuoYi foundation and GPUTrain skeleton | Login works, GPUTrain menu is permission-controlled, backend/frontend builds pass |
| 2 | User-level Training Runner | Runner registers, heartbeats, long-polls, executes a safe fixture process, and recovers it |
| 3 | GPU inventory, leases, and job state machine | Concurrent multi-GPU allocation is atomic and external processes are never allocated |
| 4 | Logs, metrics, alerts, and checkpoints | SSE resumes by offset, metrics render, and deterministic alerts fire |
| 5 | Qwen multi-agent workflow | Planner, Diagnosis, and Execution agents produce validated structured outputs behind policy gates |
| 6 | MCP and IDE integration | Scoped MCP clients can query their own experiments; dynamic tools are allowlisted and audited |
| 7 | Deployment, evaluation, and resume evidence | Docker Compose deployment, real-GPU acceptance suite, demo script, measured results, and resume copy |

Each phase updates `docs/interview/项目面试考点.md` with questions whose answers cite implemented classes and tests.

## Planning rule

Before starting a phase, create `docs/superpowers/plans/YYYY-MM-DD-gputrain-agent-phase-N-<name>.md`. The plan must use the interfaces produced by the previous phase, contain failing-test-first steps, exact verification commands, and a documentation checkpoint.
