# GPUTrain Agent Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import the official RuoYi Spring Boot 3 and Vue 3 TypeScript codebases, establish reproducible local dependencies, add bounded GPUTrain modules, and expose one permission-controlled overview page.

**Architecture:** The official RuoYi backend and frontend live under `backend/` and `frontend/`. GPUTrain business code is introduced as four focused Maven modules; Phase 1 implements only a read-only overview slice while keeping agent, monitoring, and runner protocol modules dependency-light for later phases.

**Tech Stack:** Java 17, Maven 3.9+, RuoYi-Vue Spring Boot 3 branch, Vue 3 TypeScript branch, Node.js 22 LTS, MySQL 8.4, Redis 7.4, Docker Compose, JUnit 5, Vitest.

## Global Constraints

- Work only in `/Users/weiney/Documents/个人信息/GPUTrain Agent`.
- Backend upstream: `https://github.com/yangzongzhuan/RuoYi-Vue.git`, branch `springboot3`.
- Frontend upstream: `https://github.com/yangzongzhuan/RuoYi-Vue3.git`, branch `typescript`.
- Runtime floor: Java 17 and Node.js 22 LTS.
- Use RuoYi-Vue Spring Boot 3 and RuoYi-Vue3 TypeScript; do not substitute RuoYi-Cloud or a third-party Plus fork.
- MySQL is the durable source of truth; Redis contains rebuildable state only.
- Do not add Kafka, Elasticsearch, Kubernetes, Qwen, MCP, DCGM, or training execution in Phase 1.
- Never commit passwords, API keys, tokens, `.env`, database volumes, build outputs, or IDE files.
- Update `docs/interview/项目面试考点.md` only with behavior verified by code or tests.

---

## Planned file structure

```text
GPUTrain Agent/
├── backend/                         # official RuoYi Spring Boot 3 source
│   ├── ruoyi-admin/
│   ├── ruoyi-common/
│   ├── ruoyi-framework/
│   ├── ruoyi-system/
│   ├── ruoyi-train-core/            # overview and future experiment domain
│   ├── ruoyi-train-agent/           # agent boundary; no Qwen code in Phase 1
│   ├── ruoyi-train-monitor/         # monitoring boundary
│   ├── ruoyi-train-runner-protocol/ # dependency-light runner DTO contracts
│   └── sql/gputrain_menu.sql
├── frontend/                        # official RuoYi Vue 3 TypeScript source
│   └── src/
│       ├── api/train/overview.ts
│       └── views/train/overview/index.vue
├── deploy/compose.yaml
├── .env.example
├── .gitignore
├── README.md
└── docs/
```

## Task 1: Import and verify the two official upstreams

**Files:**
- Create: `backend/**` via upstream subtree import
- Create: `frontend/**` via upstream subtree import
- Create: `docs/upstream.md`

**Interfaces:**
- Consumes: clean Git repository containing the approved design documents
- Produces: buildable official backend and frontend trees under stable prefixes

- [ ] **Step 1: Verify prerequisites and repository cleanliness**

Run:

```bash
java -version
mvn -version
node --version
npm --version
docker compose version
git status --short
```

Expected: Java reports 17 or newer, Node reports 22.x, Docker Compose is available, and only ignored `.DS_Store` files may be present. If a required runtime is absent, stop this task and install that runtime before importing source.

- [ ] **Step 2: Ignore machine-local files before importing**

Create `.gitignore` with `apply_patch`:

```gitignore
.DS_Store
.idea/
.vscode/
.env
**/target/
**/node_modules/
**/dist/
deploy/data/
*.log
```

Run:

```bash
git add .gitignore
git commit -m "chore: ignore local and generated files"
```

Expected: one commit and a clean worktree.

- [ ] **Step 3: Import backend and frontend with traceable subtree commits**

Run:

```bash
git subtree add --prefix=backend https://github.com/yangzongzhuan/RuoYi-Vue.git springboot3 --squash
git subtree add --prefix=frontend https://github.com/yangzongzhuan/RuoYi-Vue3.git typescript --squash
```

Expected: `backend/pom.xml` and `frontend/package.json` exist; each subtree command creates a commit containing its upstream revision.

- [ ] **Step 4: Record provenance**

Create `docs/upstream.md` with `apply_patch`:

```markdown
# Upstream sources

- Backend: official `yangzongzhuan/RuoYi-Vue`, `springboot3` branch, imported with `git subtree --squash`.
- Frontend: official `yangzongzhuan/RuoYi-Vue3`, `typescript` branch, imported with `git subtree --squash`.
- The exact upstream revisions are recorded in the two subtree merge commit messages in this repository.

Do not replace either source with RuoYi-Cloud or a third-party Plus fork without a new design decision.
```

- [ ] **Step 5: Run untouched upstream builds**

Run:

```bash
mvn -f backend/pom.xml clean test
npm --prefix frontend install
npm --prefix frontend run build:prod
```

Expected: Maven exits 0; npm creates the lockfile if upstream does not contain one; Vite exits 0 and creates `frontend/dist/`.

- [ ] **Step 6: Commit provenance and dependency lockfile**

Run:

```bash
git add docs/upstream.md frontend/package-lock.json
git commit -m "docs: record RuoYi upstream sources"
```

Expected: commit succeeds; `git status --short` is empty.

## Task 2: Add bounded GPUTrain Maven modules

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/ruoyi-train-core/pom.xml`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/package-info.java`
- Create: `backend/ruoyi-train-agent/pom.xml`
- Create: `backend/ruoyi-train-agent/src/main/java/com/ruoyi/train/agent/package-info.java`
- Create: `backend/ruoyi-train-monitor/pom.xml`
- Create: `backend/ruoyi-train-monitor/src/main/java/com/ruoyi/train/monitor/package-info.java`
- Create: `backend/ruoyi-train-runner-protocol/pom.xml`
- Create: `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerHeartbeat.java`
- Test: `backend/ruoyi-train-runner-protocol/src/test/java/com/ruoyi/train/runner/protocol/RunnerHeartbeatTest.java`

**Interfaces:**
- Consumes: RuoYi root Maven reactor
- Produces: four independently buildable modules and immutable `RunnerHeartbeat(String runnerId, String linuxUsername, Instant sentAt)`

- [ ] **Step 1: Write the failing runner protocol test**

```java
package com.ruoyi.train.runner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunnerHeartbeatTest {
    @Test
    void preservesRunnerIdentityAndTimestamp() {
        Instant sentAt = Instant.parse("2026-10-02T08:00:00Z");
        RunnerHeartbeat heartbeat = new RunnerHeartbeat("runner-hanwei", "hanwei", sentAt);
        assertEquals("runner-hanwei", heartbeat.runnerId());
        assertEquals("hanwei", heartbeat.linuxUsername());
        assertEquals(sentAt, heartbeat.sentAt());
    }
}
```

- [ ] **Step 2: Register the four modules and verify the test fails**

Add these entries to the root `<modules>` block:

```xml
<module>ruoyi-train-runner-protocol</module>
<module>ruoyi-train-core</module>
<module>ruoyi-train-monitor</module>
<module>ruoyi-train-agent</module>
```

Each module POM inherits the root parent and sets its matching artifact ID. The protocol module adds `org.junit.jupiter:junit-jupiter` with test scope and no Spring dependency.

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-runner-protocol test
```

Expected: FAIL because `RunnerHeartbeat` does not exist.

- [ ] **Step 3: Implement the minimal protocol record and package boundaries**

```java
package com.ruoyi.train.runner.protocol;

import java.time.Instant;
import java.util.Objects;

public record RunnerHeartbeat(String runnerId, String linuxUsername, Instant sentAt) {
    public RunnerHeartbeat {
        Objects.requireNonNull(runnerId, "runnerId");
        Objects.requireNonNull(linuxUsername, "linuxUsername");
        Objects.requireNonNull(sentAt, "sentAt");
    }
}
```

Add one `package-info.java` to each remaining module containing only a package declaration and a one-sentence Javadoc that states the module boundary.

- [ ] **Step 4: Run module and reactor tests**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-runner-protocol test
mvn -f backend/pom.xml test
```

Expected: both commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/ruoyi-train-*
git commit -m "build: add GPUTrain backend modules"
```

## Task 3: Add reproducible MySQL and Redis dependencies

**Files:**
- Create: `.env.example`
- Create: `deploy/compose.yaml`
- Create: `backend/ruoyi-admin/src/main/resources/application-local.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: official RuoYi datasource and Redis property names
- Produces: local MySQL on `127.0.0.1:3307`, Redis on `127.0.0.1:6380`, and a `local` Spring profile

- [ ] **Step 1: Add the Compose configuration**

Create `.env.example`:

```dotenv
MYSQL_DATABASE=gputrain
MYSQL_USER=gputrain
MYSQL_PASSWORD=change-me
MYSQL_ROOT_PASSWORD=change-root-me
```

Create `deploy/compose.yaml`:

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    ports: ["127.0.0.1:3307:3306"]
    volumes:
      - ./data/mysql:/var/lib/mysql
      - ../backend/sql:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 5s
      timeout: 3s
      retries: 30
  redis:
    image: redis:7.4-alpine
    ports: ["127.0.0.1:6380:6379"]
    volumes: ["./data/redis:/data"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 30
```

- [ ] **Step 2: Add environment-backed local application settings**

Create `application-local.yml` using the exact property paths present in the imported Spring Boot 3 branch. Values must resolve from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, and `REDIS_PORT`, with localhost defaults matching Compose; `DB_PASSWORD` has no committed default.

- [ ] **Step 3: Start dependencies and verify health**

Run:

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/compose.yaml config
docker compose --env-file .env -f deploy/compose.yaml up -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

Expected: Compose validation exits 0 and both services become `healthy`. Change the example passwords in local `.env` before starting; `.env` remains ignored.

- [ ] **Step 4: Verify the backend starts with the local profile**

Run:

```bash
SPRING_PROFILES_ACTIVE=local DB_PASSWORD=change-me mvn -f backend/pom.xml -pl ruoyi-admin -am spring-boot:run
```

Expected: application reaches the RuoYi startup banner without datasource or Redis connection errors. Stop it with Ctrl-C after verification.

- [ ] **Step 5: Document and commit**

Add exact local startup commands to the root `README.md`, then run:

```bash
git add .env.example deploy/compose.yaml backend/ruoyi-admin/src/main/resources/application-local.yml README.md
git commit -m "chore: add local MySQL and Redis stack"
```

## Task 4: Implement the permission-controlled overview slice

**Files:**
- Modify: `backend/ruoyi-train-core/pom.xml`
- Modify: `backend/ruoyi-admin/pom.xml`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/overview/TrainOverview.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/overview/TrainOverviewService.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/overview/TrainOverviewController.java`
- Test: `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/overview/TrainOverviewServiceTest.java`
- Test: `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/overview/TrainOverviewControllerTest.java`

**Interfaces:**
- Consumes: RuoYi `AjaxResult` and Spring Security method authorization
- Produces: `GET /train/overview` guarded by `train:overview:view`, returning `TrainOverview(status, activeExperiments, onlineRunners)`

- [ ] **Step 1: Write failing service and controller tests**

```java
@Test
void startsWithAnEmptyReadyOverview() {
    TrainOverview overview = new TrainOverviewService().getOverview();
    assertEquals("READY", overview.status());
    assertEquals(0, overview.activeExperiments());
    assertEquals(0, overview.onlineRunners());
}
```

```java
@Test
void controllerReturnsOverviewAsAjaxResultData() {
    TrainOverviewController controller = new TrainOverviewController(new TrainOverviewService());
    AjaxResult response = controller.overview();
    assertEquals(200, response.get("code"));
    assertInstanceOf(TrainOverview.class, response.get("data"));
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-core test
```

Expected: FAIL because the overview types do not exist.

- [ ] **Step 3: Implement the minimal overview slice**

```java
public record TrainOverview(String status, int activeExperiments, int onlineRunners) {}
```

```java
@Service
public class TrainOverviewService {
    public TrainOverview getOverview() {
        return new TrainOverview("READY", 0, 0);
    }
}
```

```java
@RestController
@RequestMapping("/train/overview")
public class TrainOverviewController {
    private final TrainOverviewService service;

    public TrainOverviewController(TrainOverviewService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('train:overview:view')")
    @GetMapping
    public AjaxResult overview() {
        return AjaxResult.success(service.getOverview());
    }
}
```

Add the core module as a dependency of `ruoyi-admin`; add only the RuoYi common/framework dependencies required by imports to the core POM.

- [ ] **Step 4: Run focused and reactor tests**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-core test
mvn -f backend/pom.xml test
```

Expected: both commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/ruoyi-admin/pom.xml backend/ruoyi-train-core
git commit -m "feat: add secured training overview endpoint"
```

## Task 5: Add the GPUTrain menu and Vue overview page

**Files:**
- Create: `backend/sql/gputrain_menu.sql`
- Create: `frontend/src/api/train/overview.ts`
- Create: `frontend/src/views/train/overview/index.vue`
- Create: `frontend/src/views/train/overview/overview.test.ts`

**Interfaces:**
- Consumes: `GET /train/overview`
- Produces: RuoYi dynamic route component `train/overview/index` and permission `train:overview:view`

- [ ] **Step 1: Write the failing frontend API test**

```ts
import { describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { getTrainOverview } from '@/api/train/overview'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))

describe('getTrainOverview', () => {
  it('requests the secured overview endpoint', () => {
    getTrainOverview()
    expect(request).toHaveBeenCalledWith({ url: '/train/overview', method: 'get' })
  })
})
```

- [ ] **Step 2: Add Vitest only if the imported TypeScript branch lacks it, then verify failure**

If `frontend/package.json` has no test script, add `vitest` as a dev dependency and `"test": "vitest run"`. Run:

```bash
npm --prefix frontend test -- --run src/views/train/overview/overview.test.ts
```

Expected: FAIL because `@/api/train/overview` does not exist.

- [ ] **Step 3: Implement API and page**

```ts
import request from '@/utils/request'

export interface TrainOverview {
  status: string
  activeExperiments: number
  onlineRunners: number
}

export function getTrainOverview() {
  return request<TrainOverview>({ url: '/train/overview', method: 'get' })
}
```

The Vue page must show exactly three cards: system status, active experiments, and online Runners. It loads `getTrainOverview()` on mount, shows Element Plus skeletons while loading, and displays the existing RuoYi error message mechanism on failure.

- [ ] **Step 4: Add idempotent menu SQL**

Create SQL that inserts a top-level `GPUTrain` directory and an `训练工作台` child whose component is `train/overview/index`, permission is `train:overview:view`, and visibility/status are enabled. Use `INSERT ... SELECT ... WHERE NOT EXISTS` keyed by menu name and parent to make reruns safe. Assign the permission to the built-in administrator role only; later phases add team roles.

- [ ] **Step 5: Run frontend tests and build**

Run:

```bash
npm --prefix frontend test
npm --prefix frontend run build:prod
```

Expected: tests pass and production build exits 0.

- [ ] **Step 6: Apply SQL and perform the browser smoke test**

Run the SQL against the local MySQL container, restart the backend, log in as administrator, and verify the GPUTrain menu opens the three-card page. Create a user without `train:overview:view`; verify the menu is absent and direct API access returns forbidden.

- [ ] **Step 7: Commit**

```bash
git add backend/sql/gputrain_menu.sql frontend/package.json frontend/package-lock.json frontend/src/api/train frontend/src/views/train
git commit -m "feat: add GPUTrain overview page and permission"
```

## Task 6: Document verified behavior and close Phase 1

**Files:**
- Modify: `README.md`
- Modify: `docs/interview/项目面试考点.md`
- Create: `docs/verification/phase-1-foundation.md`

**Interfaces:**
- Consumes: all Phase 1 code and test evidence
- Produces: reproducible setup instructions, code-linked interview notes, and an acceptance record

- [ ] **Step 1: Run the complete verification set**

Run:

```bash
mvn -f backend/pom.xml clean test
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env -f deploy/compose.yaml config
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 2: Write the acceptance record**

Record the exact command results, local profile used, menu/API authorization checks, commit SHA, and any environment-specific limitation in `docs/verification/phase-1-foundation.md`. Do not claim GPU, Runner, Agent, or MCP functionality in this phase.

- [ ] **Step 3: Update the interview document with verified answers**

Complete the “若依、认证与权限” section with:

- Spring Security request authentication flow in the imported RuoYi version.
- Difference between menu/button permission and Service-layer data permission.
- Why GPUTrain is a modular monolith rather than RuoYi-Cloud.
- Why MySQL is durable state while Redis is rebuildable state.
- Exact controller, SQL, test, and configuration file paths supporting each answer.

Complete the initial “Docker与部署” section with the Compose boundary and the reason training processes remain native.

- [ ] **Step 4: Expand the root README**

Document prerequisites, upstream provenance, local dependency startup, backend/frontend commands, default ports, initialization SQL, test commands, and Phase 1 scope. Do not include real credentials.

- [ ] **Step 5: Commit and confirm clean state**

```bash
git add README.md docs/interview/项目面试考点.md docs/verification/phase-1-foundation.md
git commit -m "docs: record phase one setup and interview evidence"
git status --short
```

Expected: final status is empty.

## Phase 1 completion gate

Phase 1 is complete only when:

- Untouched upstream backend and frontend baselines were verified before customization.
- All four GPUTrain Maven modules build in the root reactor.
- MySQL and Redis are reproducible with Docker Compose and no secrets are committed.
- `GET /train/overview` is protected by `train:overview:view`.
- The Vue 3 TypeScript overview page is loaded through RuoYi dynamic menus.
- A user without the permission cannot see the menu or call the endpoint.
- Backend tests, frontend tests, frontend production build, Compose validation, and `git diff --check` pass.
- Interview notes cite actual code and test paths.

After this gate, write the Phase 2 Runner plan against the imported RuoYi layout and the committed runner protocol module.
