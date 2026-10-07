# Phase 7 Plan: Deployment, Evaluation, and Project Evidence

## Goal

Finish an evidence-backed release of the integrated phases 1–6: document safe deployment/backup/upgrade/recovery, deploy only after checking the target host state, perform authenticated MCP and GPU smoke checks, and prepare a reproducible demo and resume summary.

## Steps

1. Record the current target state read-only: running jobs/processes, service versions, disk, GPU/Runner health, and active leases. Never restart a service while an owned training process is active without an explicit safe shutdown plan.
2. Build the application and Runner from this worktree; run the complete backend and frontend checks appropriate to the changed code.
3. Write a short runbook for fresh install, upgrade order, database/JAR/config backups, restore, rollback, service health, and Runner-safe shutdown. Include Phase 6 SQL migration and JWT-protected MCP endpoint.
4. If the target is idle and healthy, create timestamped JAR/config/database backups, apply the additive Phase 6 audit-table migration, deploy the built application, and record exact health and rollback evidence. Preserve training data and existing GPU state.
5. Verify anonymous MCP rejection, authorized MCP initialize/tools listing, tool permission denial, per-user task isolation, audit insertion, and a bounded real-GPU smoke job with status/log/metrics/resource evidence. Clean up only the acceptance job's own leases and processes.
6. Prepare an operator demo script and conservative resume project copy with measured evidence and remaining limitations.
7. Update verification record, interview notes, and roadmap only to match checks that actually ran.

## Verification commands

```sh
cd backend
mvn -pl ruoyi-admin -am package
cd ../frontend
npm test -- --run
npm run build:prod
```

## Deployment gate

The run is read-only until target status and active workloads are known. Back up before migration or replacement. If the target is busy, degraded, or lacks a verifiable rollback path, finish the local build/runbook/demo work and defer only the blocked remote steps with exact evidence.
