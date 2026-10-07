# Phase 6: MCP and IDE Integration Design

## Decision

Expose the existing training data through an authenticated MCP Streamable HTTP endpoint at `/mcp`. Use the official MCP Java SDK servlet transport within the current Spring Boot application and keep the existing stateless JWT authentication chain.

## Trust boundary

- The transport derives the user ID only from the authenticated Spring Security context; callers cannot supply or override it.
- Every query explicitly requests owner-scoped data with `isAdmin=false`, including administrators.
- Cross-owner task lookups return the same not-found result as nonexistent tasks.
- No mutation, execution, resource-write, or prompt tools are registered.
- Tool arguments are validated and bounded. Returned job summaries omit command details, process specifications, GPU allow/deny lists, and request keys.
- A dedicated audit record stores actor, tool, target job, outcome, safe error code, and time. It excludes arguments, returned data, log bodies, prompts, and credentials.

## Read-only tool allowlist

1. `list_my_experiments`
2. `get_my_experiment`
3. `get_experiment_logs`
4. `get_experiment_metrics`
5. `get_experiment_alerts`
6. `get_experiment_resources`

All task-specific tools require `jobId`; log queries accept a non-negative event cursor and a bounded limit. No tool accepts a user ID.

## Acceptance evidence

- Authenticated HTTP transport speaks Streamable HTTP and advertises only the six allowlisted tools.
- Anonymous requests are rejected by the existing Spring Security chain.
- Two distinct authenticated user contexts cannot list or fetch one another's jobs; cross-owner and missing jobs are indistinguishable.
- Successful and denied task-specific tool calls produce audit records without storing arguments or result content.
- Existing observability queries are reused; there are no write tools or direct GPU/process controls.
