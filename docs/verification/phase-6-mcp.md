# Phase 6 Verification: MCP and IDE Integration

## Implementation

- Streamable HTTP is served by the official MCP Java SDK Servlet transport at `/mcp` inside the existing Spring Boot application.
- The existing JWT chain authenticates requests. `/mcp` additionally requires the existing `train:job:view` permission; the transport captures the authenticated user ID from Spring Security on the request thread.
- All six registered tools are read-only: `list_my_experiments`, `get_my_experiment`, `get_experiment_logs`, `get_experiment_metrics`, `get_experiment_alerts`, `get_experiment_resources`.
- The query service always enforces owner scope with `isAdmin=false`, returns a safe task summary, and bounds history/list output. No tool accepts user ID or exposes task mutation.
- `train_mcp_audit` records actor, tool, optional target task, outcome, safe error code, and timestamp. It stores no arguments, returned content, log bodies, prompts, or credentials. Apply `backend/sql/gputrain_phase6.sql` to an existing database before enabling the app; new Compose databases initialize it as script 07.

## Verification run

Command:

```sh
cd backend
mvn -pl ruoyi-train-agent -am test
```

Result: **BUILD SUCCESS**. Protocol: 22 tests; core: 166 tests; agent: 24 tests. The Phase 6 focused tests verify the exact six-tool allowlist, per-owner list scope, cross-user and admin cross-owner denial, safe summary fields, 100-row log bound, and metadata-only audit insertion.

Build command:

```sh
mvn -pl ruoyi-admin -am -DskipTests package
```

Result: **BUILD SUCCESS**; the Spring Security permission change and packaged application compiled successfully.

The permission rule is additionally enforced at the Spring Security filter boundary, because a directly registered Servlet does not pass through MVC controller method annotations. A first live request exposed that `WebExpressionAuthorizationManager` could not resolve the `@ss` bean. It was replaced with `McpPermissionAuthorizationManager`, which checks the authenticated RuoYi `LoginUser` permission set; its regression tests cover job-view, admin wildcard, missing permission, and anonymous authentication.

## IDE client setup

Configure the MCP server URL as `https://<GPUTrain service>/mcp`, choose Streamable HTTP, and supply the existing JWT as an `Authorization: Bearer <token>` header. The token needs `train:job:view`; no separate MCP identity or API key is introduced.

## Target HTTP acceptance (2026-10-07)

- Applied the migration and exercised `/mcp` on the deployed control plane using two temporary least-privilege users. Both JWT handshakes succeeded; the tool catalog exactly matched the six-tool allowlist.
- Each user's experiment list was owner-scoped. Each user received `Experiment not found` when requesting the other's job. The manager read a safe summary, 3 log chunks, 100 bounded MCP metric rows, alerts, and 12 resource samples for managed job9; summary output contained only `jobId`, `jobUuid`, `status`, `gpuCount`, `createdAt`, and `updatedAt`.
- The observer's job-create request was denied (`code=403`). After its job-view permission was removed and a fresh JWT issued, MCP initialization was rejected (`code=401`). Nine metadata-only audit rows persisted: 7 successful calls and 2 cross-owner denials.
- Both temporary accounts and roles were deleted, JWTs revoked, and Runner owner restored. Audit and job evidence remain in the database. External HTTPS reverse-proxy forwarding and an IDE-specific client configuration were not tested; the control plane listens on loopback HTTP.
