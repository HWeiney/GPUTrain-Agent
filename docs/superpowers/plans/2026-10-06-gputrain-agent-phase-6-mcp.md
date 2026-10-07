# Phase 6 Implementation Plan: MCP and IDE Integration

## Goal

Implement the approved design in `docs/superpowers/specs/2026-10-06-gputrain-agent-phase-6-mcp-design.md` using the job and observability services from Phases 3–4.

## Steps

1. Add the MCP Java SDK servlet transport dependency and a Spring servlet registration at `/mcp`; keep it behind existing JWT authentication.
2. Add an MCP query service that derives identity from request context, exposes only safe job summaries and the six approved read operations, enforces owner scope with `isAdmin=false`, and bounds log results.
3. Register only the six approved tools with minimal JSON schemas; omit resources, prompts, and all mutation capabilities.
4. Add a dedicated audit table and mapper/service. Record actor, tool, optional job, outcome, safe error code, and timestamp; never record arguments or returned/log content.
5. Write failing-first service/tool/transport tests: tool allowlist, identity extraction, success/denial auditing, argument bounds, anonymous transport rejection, and cross-user isolation.
6. Run focused backend tests and module compilation, then update the verification report, interview notes, roadmap, and phase status.

## Verification commands

```sh
cd backend
mvn -pl ruoyi-train-agent -am test
mvn -pl ruoyi-admin -am -DskipTests package
```

## Documentation checkpoint

Record exact test/build results in `docs/verification/phase-6-mcp.md`; update `docs/interview/项目面试考点.md` and the phase-6 roadmap status only after evidence is collected.
