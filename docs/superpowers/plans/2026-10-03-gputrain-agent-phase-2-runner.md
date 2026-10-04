# GPUTrain Agent Phase 2 User Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a user-level Java Runner that securely registers with the RuoYi control plane, heartbeats, long-polls durable commands, executes a constrained fixture process, and reconciles that process after a Runner restart.

**Architecture:** `ruoyi-train-runner-protocol` owns transport-neutral records and HMAC canonicalization; `ruoyi-train-core` owns MySQL state, Redis replay protection, management APIs, and Runner APIs; a new `ruoyi-train-runner` executable uses JDK `HttpClient` and `ProcessBuilder` without depending on RuoYi. MySQL is the durable source of truth, Redis only guards nonces and online TTLs, and local atomic state files let the Runner recover process identity.

**Tech Stack:** Java 17, Maven 3.9+, Spring Boot 3.5.16, Spring Security, MyBatis 3, MySQL 8.4, Redis 7.4, Jackson, JDK `HttpClient`, JUnit 5, Mockito, Vue/Vitest regression tests.

## Global Constraints

- The control plane never stores Linux passwords or SSH private keys and never starts training over SSH.
- Runtime Runner traffic uses HTTPS plus HMAC-SHA256; explicit local/test configuration may permit HTTP only from a loopback remote address.
- Runner secrets are AES-256-GCM encrypted at rest with a 256-bit master key supplied outside Git.
- A Runner accepts structured process fields only and never invokes `sh -c` or another command shell.
- Files and processes are limited to the configured allowed root; the Linux acceptance root is `/data/hanw/hw_code/gputrain-test` for user `hanw`.
- MySQL owns durable Runner, enrollment, command, and process state; Redis owns nonce and online TTL only.
- Every state-changing operation is idempotent; a `RUNNING` command is never automatically requeued from lease expiry.
- Java source targets version 17 and follows the existing RuoYi brace and package conventions.
- No phase-three GPU discovery, scheduling, lease, or full training-job behavior is added in this plan.

---

## Planned File Structure

### Protocol module

- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerCommandType.java`: command kind enum.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerCommandStatus.java`: durable command state enum and terminal-state rule.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerProcessStatus.java`: observed process states, including `UNKNOWN` and `IDENTITY_MISMATCH`.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerErrorCode.java`: stable cross-process error codes.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerProtocolException.java`: error-code-bearing protocol exception.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/ProcessSpec.java`: structured process request.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerRegistration.java`: first-registration request.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerCredentials.java`: one-time registration response.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerCommandLease.java`: leased command envelope.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerCommandReport.java`: idempotent status report.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerProcessSnapshot.java`: recovery/reconciliation payload.
- `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerRequestSigner.java`: canonical request and HMAC implementation.

### Control-plane module

- `backend/sql/gputrain_runner.sql`: four tables, constraints, indexes, and Runner permissions.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/domain/*`: persistence records only.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/mapper/*`: MyBatis mapper contracts.
- `backend/ruoyi-train-core/src/main/resources/mapper/train/*Mapper.xml`: MySQL statements, including atomic enrollment consumption and command lease.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/*`: token digest, AES-GCM cipher, HMAC verifier, and Redis nonce store.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/service/*`: enrollment, heartbeat, command, and reconciliation use cases.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerAdminController.java`: JWT/permission-protected management API.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerApiController.java`: anonymous-at-Spring-layer endpoints with explicit Runner authentication.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerAuthenticationInterceptor.java`: verifies signed Runner requests before controllers.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/config/RunnerWebConfiguration.java`: registers only `/runner-api/**` with the interceptor.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/config/RunnerSecurityProperties.java`: clock skew, TTL, HTTPS rule, and master key configuration.
- `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RunnerTransportSecurityGuard.java`: HTTPS enforcement and loopback-only local HTTP for credential endpoints.

### Runner executable

- `backend/ruoyi-train-runner/pom.xml`: standalone shaded executable.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/RunnerApplication.java`: lifecycle and polling loop.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/config/RunnerConfig.java`: validated properties-file model.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/client/RunnerControlClient.java`: registration, signed requests, and JSON transport.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessPolicy.java`: path, executable, argument, and environment rules.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ValidatedProcessSpec.java`: canonical paths and filtered environment approved by policy.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessPolicyException.java`: stable policy failure with `RunnerErrorCode`.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessLookup.java`: injectable adapter over `ProcessHandle` for identity tests.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessRecovery.java`: recovered state or identity error.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ManagedProcess.java`: immutable local process identity.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessStateStore.java`: atomic JSON state files.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessSupervisor.java`: start, observe, stop, and recover behavior.
- `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/fixture/FixtureMain.java`: deterministic success, failure, and sleep fixture.
- `deploy/runner/gputrain-runner.service`: `systemd --user` unit.
- `deploy/runner/runner.properties.example`: non-secret configuration example.

---

### Task 1: Freeze the shared protocol and signature contract

**Files:**
- Modify: `backend/ruoyi-train-runner-protocol/src/main/java/com/ruoyi/train/runner/protocol/RunnerHeartbeat.java`
- Create: protocol files listed above
- Test: `backend/ruoyi-train-runner-protocol/src/test/java/com/ruoyi/train/runner/protocol/RunnerProtocolTest.java`
- Test: `backend/ruoyi-train-runner-protocol/src/test/java/com/ruoyi/train/runner/protocol/RunnerRequestSignerTest.java`

**Interfaces:**
- Produces: `ProcessSpec(String workingDirectory, String executable, List<String> arguments, Map<String,String> environment, long timeoutSeconds, String idempotencyKey)`.
- Produces: `RunnerRequestSigner.canonical(String runnerId, long timestamp, String nonce, String method, String path, byte[] body)` and `sign(byte[] secret, String canonical)`.
- Produces: request/response records consumed unchanged by control plane and Runner.

- [ ] **Step 1: Write failing protocol validation tests**

```java
@Test
void processSpecDefensivelyCopiesArgumentsAndEnvironment()
{
    List<String> arguments = new ArrayList<>(List.of("--mode", "sleep"));
    Map<String, String> environment = new HashMap<>(Map.of("CUDA_VISIBLE_DEVICES", "0"));
    ProcessSpec spec = new ProcessSpec("/tmp/work", "/usr/bin/java", arguments, environment, 60, "fixture-1");
    arguments.add("changed");
    environment.put("PATH", "/tmp");
    assertEquals(List.of("--mode", "sleep"), spec.arguments());
    assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "0"), spec.environment());
}

@Test
void finalCommandStatesCannotTransition()
{
    assertTrue(RunnerCommandStatus.SUCCEEDED.isTerminal());
    assertFalse(RunnerCommandStatus.RUNNING.isTerminal());
    assertThrows(IllegalStateException.class,
        () -> RunnerCommandStatus.SUCCEEDED.requireTransitionTo(RunnerCommandStatus.RUNNING));
}
```

- [ ] **Step 2: Run the protocol tests and confirm compilation fails**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-runner-protocol -am -Dtest=RunnerProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `ProcessSpec` and `RunnerCommandStatus` do not exist.

- [ ] **Step 3: Implement immutable records, enums, and state transition rules**

```java
public record ProcessSpec(String workingDirectory, String executable,
        List<String> arguments, Map<String, String> environment,
        long timeoutSeconds, String idempotencyKey)
{
    public ProcessSpec
    {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(executable, "executable");
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
}

public enum RunnerCommandStatus
{
    QUEUED, LEASED, RUNNING, SUCCEEDED, FAILED, STOPPED, REJECTED;

    public boolean isTerminal()
    {
        return this == SUCCEEDED || this == FAILED || this == STOPPED || this == REJECTED;
    }

    public void requireTransitionTo(RunnerCommandStatus next)
    {
        boolean allowed = switch (this) {
            case QUEUED -> next == LEASED || next == REJECTED;
            case LEASED -> next == RUNNING || next == REJECTED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == STOPPED;
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("Illegal command transition: " + this + " -> " + next);
    }
}
```

Create the transport types with these exact shapes:

```java
public enum RunnerCommandType { START_PROCESS, STOP_PROCESS }

public enum RunnerProcessStatus { RUNNING, SUCCEEDED, FAILED, STOPPED, UNKNOWN, IDENTITY_MISMATCH }

public final class RunnerProtocolException extends RuntimeException
{
    private final RunnerErrorCode code;
    public RunnerProtocolException(RunnerErrorCode code, String message) { super(message); this.code = code; }
    public RunnerErrorCode code() { return code; }
}

public record RunnerRegistration(long enrollmentId, String enrollmentToken,
        String runnerName, String linuxUsername) { }

public record RunnerCredentials(String runnerId, String runnerName, String secret)
{
    @Override public String toString() { return "RunnerCredentials[runnerId=" + runnerId + "]"; }
}

public record RunnerCommandLease(UUID commandId, RunnerCommandType type,
        ProcessSpec process, UUID targetCommandId, Instant leaseUntil, int attempt) { }

public record RunnerCommandReport(RunnerCommandStatus previousStatus,
        RunnerCommandStatus status, Long pid, Instant processStartedAt,
        Integer exitCode, RunnerErrorCode errorCode, String errorMessage, int attempt) { }

public record RunnerProcessSnapshot(UUID commandId, long pid, Instant processStartedAt,
        String executable, String argumentsSha256, RunnerProcessStatus status,
        Integer exitCode, RunnerErrorCode errorCode) { }
```

Compact constructors reject null required fields and enforce the command-specific invariant: `START_PROCESS` has `process` and no target ID; `STOP_PROCESS` has `targetCommandId` and no process. `RunnerErrorCode` contains every stable code enumerated in the approved spec.

- [ ] **Step 4: Write failing deterministic HMAC tests**

```java
@Test
void canonicalRequestAndSignatureAreDeterministic()
{
    String canonical = RunnerRequestSigner.canonical("runner-1", 1_780_000_000L, "nonce-1",
        "post", "/runner-api/v1/heartbeat", "{}".getBytes(StandardCharsets.UTF_8));
    assertEquals("runner-1\n1780000000\nnonce-1\nPOST\n/runner-api/v1/heartbeat\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", canonical);
    assertEquals("XxcJvlUb_Wfx2jLNRPnJvZLJWmVHzkB-mlMmxPtQ0-E",
        RunnerRequestSigner.sign("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), canonical));
}
```

- [ ] **Step 5: Implement `RunnerRequestSigner` with SHA-256, HmacSHA256, uppercase methods, UTF-8, and Base64URL without padding**

```java
public static String canonical(String runnerId, long timestamp, String nonce, String method, String path, byte[] body)
{
    return String.join("\n", runnerId, Long.toString(timestamp), nonce,
        method.toUpperCase(Locale.ROOT), path, HexFormat.of().formatHex(sha256(body)));
}

public static String sign(byte[] secret, String canonical)
{
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
}
```

- [ ] **Step 6: Run tests and commit the protocol slice**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-runner-protocol -am test`

Expected: existing heartbeat test plus new protocol and HMAC tests PASS.

```bash
git add backend/ruoyi-train-runner-protocol
git commit -m "feat: define runner protocol and request signing"
```

### Task 2: Add durable Runner schema and MyBatis persistence

**Files:**
- Create: `backend/sql/gputrain_runner.sql`
- Modify: `deploy/compose.yaml`
- Modify: `backend/ruoyi-train-core/pom.xml`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/domain/RunnerRecord.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/domain/RunnerEnrollmentRecord.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/domain/RunnerCommandRecord.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/domain/RunnerProcessRecord.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/mapper/RunnerMapper.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/mapper/RunnerEnrollmentMapper.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/mapper/RunnerCommandMapper.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/mapper/RunnerProcessMapper.java`
- Create: matching XML files under `backend/ruoyi-train-core/src/main/resources/mapper/train/`
- Test: `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/runner/RunnerSchemaContractTest.java`

**Interfaces:**
- Produces: `RunnerEnrollmentMapper.consume(enrollmentId, tokenDigest, consumedAt)` returning exactly one only for an unexpired, unconsumed row, while clearing its temporary token ciphertext in the same update.
- Produces: `RunnerCommandMapper.leaseNext(runnerId, now, leasedUntil)` with transaction-level row locking.
- Produces: unique database keys on Runner name, enrollment token digest, enrollment idempotency key, and `(runner_id, idempotency_key)`.
- The enrollment idempotency key uses MySQL `utf8mb4_0900_bin` collation. Keep `backend/sql/gputrain_runner.sql` as the single fresh-install/manual-upgrade source: conditionally add missing token-ciphertext and idempotency columns, backfill legacy null/blank keys with row-specific migration values, make the key `NOT NULL` with binary collation, and conditionally add its unique index so reruns are safe.
- The command idempotency key also uses `utf8mb4_0900_bin` in fresh schemas; the same SQL file conditionally alters existing command tables to that collation. The command's `attempt_count` is the durable lease generation and increments on every lease.

- [ ] **Step 1: Write a failing schema contract test that reads the SQL resource**

```java
@Test
void schemaContainsDurableTablesAndIdempotencyConstraint() throws IOException
{
    String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
    assertAll(
        () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner ")),
        () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_enrollment ")),
        () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_command ")),
        () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_process ")),
        () -> assertTrue(sql.contains("UNIQUE KEY uk_runner_idempotency (runner_id, idempotency_key)")));
}
```

- [ ] **Step 2: Run the test and confirm it fails because the SQL file is absent**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=RunnerSchemaContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL with `NoSuchFileException` for `backend/sql/gputrain_runner.sql`.

- [ ] **Step 3: Add the four-table MySQL schema and initialization order**

The SQL must use `SET NAMES utf8mb4`, `BIGINT` identifiers, `DATETIME(3)` timestamps, `VARCHAR(64)` statuses, `JSON` payload/result columns, foreign keys between command/process and Runner records, and these exact indexes:

The enrollment table includes `token_digest`, nullable `token_ciphertext`, and a required `idempotency_key`. The token ciphertext is set to `NULL` when registration succeeds.

```sql
UNIQUE KEY uk_runner_name (runner_name),
UNIQUE KEY uk_enrollment_digest (token_digest),
UNIQUE KEY uk_enrollment_idempotency (idempotency_key),
UNIQUE KEY uk_runner_idempotency (runner_id, idempotency_key),
KEY idx_command_poll (runner_id, status, created_at),
UNIQUE KEY uk_process_command (command_id)
```

Mount it as `/docker-entrypoint-initdb.d/04-gputrain-runner.sql:ro` after the existing menu SQL.

- [ ] **Step 4: Add records, mapper interfaces, and exact MyBatis statements**

Use explicit mapper methods rather than a generic CRUD base. The command lease XML must select one eligible row using:

```sql
SELECT command_id
FROM train_runner_command
WHERE runner_id = #{runnerId}
  AND (status = 'QUEUED' OR (status = 'LEASED' AND lease_until &lt; #{now}))
ORDER BY created_at, command_id
LIMIT 1
FOR UPDATE SKIP LOCKED
```

Then update only that ID to `LEASED`; never include `RUNNING` in the lease-expiry predicate. Enrollment consumption must update only `consumed_at IS NULL AND expires_at > #{consumedAt}`, check the token digest in the same statement, and clear `token_ciphertext` atomically. Enrollment consumption and Runner creation share one transaction so a Runner insert failure restores the encrypted token.

- [ ] **Step 5: Run schema, compile, and Compose checks**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-core -am test
docker compose --env-file .env -f deploy/compose.yaml config -q
git diff --check
```

Expected: tests PASS, Compose exits 0, diff check emits no output.

- [ ] **Step 6: Commit persistence**

```bash
git add backend/sql/gputrain_runner.sql deploy/compose.yaml backend/ruoyi-train-core
git commit -m "feat: persist runner identities and commands"
```

### Task 3: Implement one-time enrollment and encrypted Runner credentials

**Files:**
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/config/RunnerSecurityProperties.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/EnrollmentTokenDigester.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RunnerCredentialCipher.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RunnerTransportSecurityGuard.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/service/RunnerEnrollmentService.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerAdminController.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerRegistrationController.java`
- Modify: `backend/ruoyi-admin/src/main/resources/application.yml`
- Modify: `backend/ruoyi-admin/src/main/resources/application-local.yml`
- Test: corresponding `*Test.java` files under `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/runner/`

**Interfaces:**
- Produces: `EnrollmentGrant createEnrollment(long ownerUserId, String runnerName, String linuxUsername, String allowedRoot, Instant expiresAt, String idempotencyKey)`.
- Produces: `RunnerCredentials register(RunnerRegistration request, Instant now)`.
- Produces: `RunnerCredentialCipher.encrypt(byte[] plaintext)` and `decrypt(String encoded)` using `version.iv.ciphertext` Base64URL fields.
- Enrollment creation requires an idempotency key. A matching key with identical owner/name/Linux user/root/expiry returns the existing token by decrypting its temporary AES-GCM ciphertext. Reusing a key with different fields returns `ENROLLMENT_INVALID`. A unique database key handles concurrent duplicate insertion; the losing request rereads and returns the committed winner. Successful registration clears the token ciphertext in the conditional consume update; transaction rollback preserves it if Runner creation fails. Replays after successful consumption return `ENROLLMENT_ALREADY_USED`.
- `createEnrollment` and `register` use `READ_COMMITTED` isolation so duplicate-key winner rereads and concurrent-consume classification see the latest committed row. `existingGrant` also compares the stored idempotency key with exact Java string equality.
- Both credential-bearing endpoints call `RunnerTransportSecurityGuard`: HTTPS always passes; HTTP passes only when `allow-loopback-http` is enabled, `require-https` is false, and the remote address is loopback. Other HTTP requests return `RUNNER_HTTPS_REQUIRED`.
- `RunnerProtocolExceptionHandler` is a highest-precedence advice scoped to Runner web controllers. It returns `{errorCode, message}` with a fixed safe message; `RUNNER_HTTPS_REQUIRED` maps to HTTP 403 and other protocol errors to HTTP 400, preserving protocol codes instead of RuoYi's generic AjaxResult runtime envelope.

- [ ] **Step 1: Write failing AES-GCM and token digest tests**

```java
@Test
void cipherRoundTripsWithoutEmbeddingPlaintext()
{
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    RunnerCredentialCipher cipher = new RunnerCredentialCipher(key, new SecureRandom(new byte[] { 1, 2, 3 }));
    String encrypted = cipher.encrypt("runner-secret".getBytes(StandardCharsets.UTF_8));
    assertFalse(encrypted.contains("runner-secret"));
    assertArrayEquals("runner-secret".getBytes(StandardCharsets.UTF_8), cipher.decrypt(encrypted));
}

@Test
void tokenDigestUsesServerPepperAndConstantTimeComparison()
{
    EnrollmentTokenDigester digester = new EnrollmentTokenDigester("pepper-value".getBytes(StandardCharsets.UTF_8));
    String digest = digester.digest("one-time-token");
    assertTrue(digester.matches("one-time-token", digest));
    assertFalse(digester.matches("wrong-token", digest));
}
```

- [ ] **Step 2: Run tests and confirm missing production classes**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=RunnerCredentialCipherTest,EnrollmentTokenDigesterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL at test compilation.

- [ ] **Step 3: Implement AES-256-GCM and HMAC-based token digests**

Use a 12-byte random IV, 128-bit GCM authentication tag, `AES/GCM/NoPadding`, version `v1`, and `MessageDigest.isEqual` for comparisons. `RunnerSecurityProperties` rejects decoded master keys not exactly 32 bytes and rejects blank token pepper.

- [ ] **Step 4: Write failing enrollment service tests with in-memory fake mappers**

```java
@Test
void enrollmentCanBeConsumedOnlyOnce()
{
    EnrollmentGrant grant = service.createEnrollment(1L, "runner-hanw", "hanw",
        "/data/hanw/hw_code/gputrain-test", NOW.plusSeconds(600), "request-key-1");
    RunnerCredentials first = service.register(new RunnerRegistration(
        grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW);
    assertEquals("runner-hanw", first.runnerName());
    assertThrows(RunnerProtocolException.class, () -> service.register(new RunnerRegistration(
        grant.enrollmentId(), grant.plaintextToken(), "runner-hanw", "hanw"), NOW));
}
```

- [ ] **Step 5: Implement transactional enrollment and controllers**

`RunnerAdminController` exposes `POST /train/runners/enrollments`, requires `train:runner:enroll`, and takes owner user ID, Runner name, Linux username, allowed root, expiry, and a required idempotency key. Identical retries return the same token until consumption; conflicting key reuse is rejected. `RunnerRegistrationController` exposes `POST /runner-api/v1/register`, is annotated `@Anonymous`, consumes the grant transactionally, clears its temporary ciphertext in the conditional update, creates the Runner, and returns the new secret once. If creation fails, rollback restores the unconsumed grant and its ciphertext. Neither response object exposes secrets through `toString()`.

- [ ] **Step 6: Add configuration without defaults for secrets**

```yaml
gputrain:
  runner-security:
    master-key: ${RUNNER_CREDENTIAL_MASTER_KEY:}
    enrollment-pepper: ${RUNNER_ENROLLMENT_PEPPER:}
    allowed-clock-skew: 2m
    nonce-ttl: 5m
    max-request-body-bytes: 65536
    require-https: true
    allow-loopback-http: false
```

In `application-local.yml`, set `require-https: false` and `allow-loopback-http: true` only for loopback development. The guard still rejects HTTP from every non-loopback remote address. Retain empty environment-backed secret fields so missing secrets fail when Runner security beans are enabled.

- [ ] **Step 7: Run enrollment tests and commit**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-core -am test`

Expected: all core and protocol tests PASS.

```bash
git add backend/ruoyi-train-core backend/ruoyi-admin/src/main/resources/application*.yml
git commit -m "feat: enroll runners with encrypted credentials"
```

### Task 4: Authenticate heartbeats and prevent replay

**Files:**
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RunnerNonceStore.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RedisRunnerNonceStore.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/RunnerRequestVerifier.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/security/SignedRunnerRequest.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/CachedBodyHttpServletRequest.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerAuthenticationInterceptor.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/config/RunnerWebConfiguration.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/service/RunnerHeartbeatService.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerApiController.java`
- Test: `RunnerRequestVerifierTest.java`, `RunnerHeartbeatServiceTest.java`, `RunnerAuthenticationInterceptorTest.java`

**Interfaces:**
- Consumes: `RunnerRequestSigner`, encrypted Runner secrets, and `RunnerMapper`.
- Produces: `SignedRunnerRequest(String runnerId, long timestamp, String nonce, String signature, String method, String path, byte[] body)`; its compact constructor clones `body`, and `body()` returns a clone.
- Produces: authenticated `runnerId` request attribute named `gputrain.runnerId`.
- Produces: `RunnerHeartbeatService.record(String runnerId, RunnerHeartbeat heartbeat, Instant receivedAt)`.

- [ ] **Step 1: Write verifier tests for success, tampering, skew, and replay**

```java
@Test
void rejectsReplayedNonceAfterValidSignature()
{
    SignedRunnerRequest request = signedRequest("nonce-1", NOW, "{}");
    assertEquals("runner-1", verifier.verify(request));
    RunnerProtocolException error = assertThrows(RunnerProtocolException.class, () -> verifier.verify(request));
    assertEquals(RunnerErrorCode.RUNNER_REPLAY_DETECTED, error.code());
}

@Test
void rejectsModifiedBody()
{
    SignedRunnerRequest request = signedRequest("nonce-2", NOW, "{}").withBody("{\"changed\":true}");
    assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID,
        assertThrows(RunnerProtocolException.class, () -> verifier.verify(request)).code());
}
```

- [ ] **Step 2: Run verifier tests and confirm they fail**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=RunnerRequestVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because verifier and nonce abstractions do not exist.

- [ ] **Step 3: Implement signature verification and atomic Redis nonce insertion**

`RunnerNonceStore.claim(runnerId, nonce, ttl)` returns `true` only once. `RedisRunnerNonceStore` calls `redisTemplate.opsForValue().setIfAbsent(key, "1", ttl)` and treats Redis exceptions as authentication failure. Verify time and signature before claiming the nonce; compare decoded signatures with `MessageDigest.isEqual`.

- [ ] **Step 4: Add cached-body authentication only for `/runner-api/v1/**` except `/register`**

The interceptor applies `RunnerTransportSecurityGuard` before HMAC, reads exact body bytes, validates `X-Runner-Id`, `X-Runner-Timestamp`, `X-Runner-Nonce`, and `X-Runner-Signature`, stores authenticated Runner ID as a request attribute, and returns a JSON error with the stable error code on failure. Registration remains unauthenticated at the HMAC layer but is protected by its one-time token and existing transport guard. The signed Runner API body limit is configurable through `gputrain.runner-security.max-request-body-bytes` (default 65536). Reject declared oversized requests before reading, bound unknown-length reads, and emit HTTP 413 with `RUNNER_REQUEST_TOO_LARGE` from the filter.

- [ ] **Step 5: Implement heartbeat validation and online TTL**

Require the path-authenticated Runner ID to equal the body Runner ID and the persisted Linux username to equal the heartbeat username. Update the existing MySQL `last_seen_at` column and write `gputrain:runner:online:<runnerId>` with a 90-second TTL. Map an online Redis write failure to safe HTTP 503 with `RUNNER_TEMPORARILY_UNAVAILABLE`; the MySQL update may already have completed, and retries remain idempotent.

- [ ] **Step 6: Run focused tests, full core tests, and commit**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=RunnerRequestVerifierTest,RunnerAuthenticationInterceptorTest,RunnerHeartbeatServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f backend/pom.xml -pl ruoyi-train-core -am test
```

Expected: both commands PASS.

```bash
git add backend/ruoyi-train-core
git commit -m "feat: authenticate runner heartbeats"
```

### Task 5: Implement durable long-poll command leasing and idempotent reports

**Files:**
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/service/RunnerCommandService.java`
- Modify: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerAdminController.java`
- Modify: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerApiController.java`
- Test: `backend/ruoyi-train-core/src/test/java/com/ruoyi/train/core/runner/RunnerCommandServiceTest.java`

**Interfaces:**
- Produces: `RunnerCommandRecord createFixtureCommand(String runnerId, ProcessSpec spec, long creatorUserId)`.
- Produces: `Optional<RunnerCommandLease> leaseNext(String runnerId, Instant now, Duration leaseDuration)`.
- Produces: `RunnerCommandRecord report(String runnerId, UUID commandId, RunnerCommandReport report)`.
- Produces: `requestStop(String runnerId, UUID commandId)` as a separate idempotent `STOP_PROCESS` command keyed by target command ID.

- [ ] **Step 1: Write failing service tests for duplicate create, lease expiry, and final-state immutability**

```java
@Test
void duplicateIdempotencyKeyReturnsOriginalCommand()
{
    ProcessSpec spec = fixtureSpec("same-key");
    RunnerCommandRecord first = service.createFixtureCommand("runner-1", spec, 1L);
    RunnerCommandRecord second = service.createFixtureCommand("runner-1", spec, 1L);
    assertEquals(first.getCommandId(), second.getCommandId());
}

@Test
void runningCommandIsNeverReLeased()
{
    RunnerCommandRecord command = queuedCommand();
    service.leaseNext("runner-1", NOW, Duration.ofSeconds(30));
    service.report("runner-1", command.getCommandId(), report(RunnerCommandStatus.RUNNING));
    assertTrue(service.leaseNext("runner-1", NOW.plusSeconds(60), Duration.ofSeconds(30)).isEmpty());
}
```

- [ ] **Step 2: Run command tests and confirm failure**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-core -am -Dtest=RunnerCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `RunnerCommandService` does not exist.

- [ ] **Step 3: Implement transactional lease and compare-and-set status reports**

Create or find by `(runnerId, idempotencyKey)`, comparing the raw key exactly on duplicate lookup and reserving `stop:` case-insensitively. Lease inside a transaction using the Task 2 mapper lock and return the incremented `attempt_count`. Every report carries that attempt. SQL updates compare Runner ID, command ID, attempt, and expected state; leased-state updates also compare lease expiry. A stale attempt is rejected even before the newer Runner reports. Duplicate reports succeed only for the same attempt and already-persisted state; terminal states reject different next states. Return safe stable errors for missing/foreign commands (404) and stale/expired leases or status conflicts (409).

- [ ] **Step 4: Expose management and Runner endpoints**

```text
POST /train/runners/{runnerId}/fixture-commands   permission train:runner:command
POST /train/runners/{runnerId}/commands/{id}/stop permission train:runner:command
GET  /runner-api/v1/commands/poll?timeoutSeconds=20
POST /runner-api/v1/commands/{id}/reports
```

Use Spring `DeferredResult` with a maximum 20-second wait. The database lease happens before returning a command; a timeout returns HTTP 204. Coordinate the timeout callback with each in-flight lease and result delivery so a committed lease cannot be silently lost after a 204. Do not hold a database transaction open while waiting. Cover the race with a latch-controlled concurrency test.

- [ ] **Step 5: Add permission rows idempotently to `gputrain_runner.sql`**

Create hidden button permissions `train:runner:enroll`, `train:runner:view`, and `train:runner:command`, attach them under the existing GPUTrain menu, and assign them only to role ID `1` using `NOT EXISTS` guards.

- [ ] **Step 6: Verify and commit the command queue**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-core -am test
git diff --check
```

Expected: tests PASS and diff check emits no output.

```bash
git add backend/sql/gputrain_runner.sql backend/ruoyi-train-core
git commit -m "feat: lease runner commands idempotently"
```

### Task 6: Build the standalone Runner client and safe process supervisor

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/ruoyi-train-runner/pom.xml`
- Create: all Runner executable files listed in Planned File Structure except deployment files
- Test: `RunnerConfigTest.java`, `RunnerControlClientTest.java`, `ProcessPolicyTest.java`, `ProcessSupervisorTest.java`, `FixtureMainTest.java`

**Interfaces:**
- Consumes: Task 1 protocol records and `/runner-api/v1/**` endpoints.
- Produces: `RunnerConfig.load(Path)` with `controlPlaneUrl`, `runnerId`, credential file, allowed root, executable allowlist, environment allowlist, heartbeat interval, and polling timeout.
- Produces: `ProcessPolicy.validate(ProcessSpec)` returning a resolved immutable `ValidatedProcessSpec`.
- Produces: `ProcessSupervisor.start(UUID commandId, ValidatedProcessSpec spec)`, `stop(UUID commandId)`, and `observe(UUID commandId)`.
- Produces: `ProcessLookup.find(long pid)` returning an immutable view of PID, start time, executable, arguments, liveness, and destroy operations; production delegates to `ProcessHandle`, tests use a fake.

- [ ] **Step 1: Add the Maven module and write failing configuration tests**

```java
@Test
void rejectsNonLoopbackHttpControlPlane()
{
    Properties properties = validProperties();
    properties.setProperty("controlPlaneUrl", "http://10.31.118.10:8080");
    assertThrows(IllegalArgumentException.class, () -> RunnerConfig.from(properties));
}

@Test
void acceptsHttpsAndCanonicalizesAllowedRoot() throws IOException
{
    Properties properties = validProperties();
    properties.setProperty("controlPlaneUrl", "https://control.example.test");
    assertEquals(tempDir.toRealPath(), RunnerConfig.from(properties).allowedRoot());
}
```

- [ ] **Step 2: Run the Runner tests and confirm module/class failures**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-runner -am test`

Expected: FAIL until the module and classes are created.

- [ ] **Step 3: Implement validated config and signed JDK HTTP transport**

Read the Runner secret from a separate file, reject group/world-readable POSIX permissions on Linux, generate a 128-bit random nonce per request, sign exact serialized bytes, and never log request headers containing credentials. Registration is the only unsigned method.

- [ ] **Step 4: Write failing path, symlink, environment, and shell-metacharacter tests**

```java
@Test
void rejectsSymlinkThatEscapesAllowedRoot() throws IOException
{
    Path outside = Files.createTempDirectory("outside");
    Path link = Files.createSymbolicLink(tempDir.resolve("escape"), outside);
    ProcessSpec spec = fixtureSpec(link.toString(), javaExecutable.toString(), List.of("--mode", "sleep"));
    assertEquals(RunnerErrorCode.WORK_DIR_SYMLINK_ESCAPE,
        assertThrows(ProcessPolicyException.class, () -> policy.validate(spec)).code());
}

@Test
void preservesShellMetacharactersAsOneArgument()
{
    ValidatedProcessSpec spec = policy.validate(fixtureSpec(tempDir.toString(), javaExecutable.toString(),
        List.of("literal;touch", "never-created")));
    assertEquals("literal;touch", spec.arguments().get(0));
}
```

- [ ] **Step 5: Implement `ProcessPolicy` and `ProcessSupervisor` without a shell**

Resolve all existing paths with `toRealPath()`, require `resolved.startsWith(allowedRoot)`, use an exact `Set<Path>` executable allowlist, reject any environment key outside the allowlist, clear inherited environment entries that are not explicitly retained, and invoke:

```java
ProcessBuilder builder = new ProcessBuilder(commandElements);
builder.directory(validated.workingDirectory().toFile());
builder.environment().clear();
builder.environment().putAll(validated.environment());
builder.redirectOutput(validated.stdoutPath().toFile());
builder.redirectError(validated.stderrPath().toFile());
Process process = builder.start();
```

Do not add `sh`, `bash`, `cmd.exe`, or `powershell` anywhere in the execution path.

- [ ] **Step 6: Add deterministic fixture modes**

`FixtureMain` accepts only `--mode success`, `--mode failure`, or `--mode sleep --seconds N`; success prints one line and exits 0, failure prints to stderr and exits 17, and sleep prints its PID before sleeping.

- [ ] **Step 7: Run Runner and reactor tests, then commit**

Run:

```bash
mvn -f backend/pom.xml -pl ruoyi-train-runner -am test
mvn -f backend/pom.xml test
```

Expected: Runner tests and all 12 Maven modules PASS.

```bash
git add backend/pom.xml backend/ruoyi-train-runner
git commit -m "feat: execute constrained runner processes"
```

### Task 7: Persist local process identity and reconcile after restart

**Files:**
- Create: `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ManagedProcess.java`
- Create: `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessStateStore.java`
- Modify: `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/process/ProcessSupervisor.java`
- Modify: `backend/ruoyi-train-runner/src/main/java/com/ruoyi/train/runner/RunnerApplication.java`
- Create: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/service/RunnerReconciliationService.java`
- Modify: `backend/ruoyi-train-core/src/main/java/com/ruoyi/train/core/runner/web/RunnerApiController.java`
- Test: `ProcessStateStoreTest.java`, `ProcessRecoveryTest.java`, `RunnerReconciliationServiceTest.java`

**Interfaces:**
- Produces: `ProcessStateStore.save(ManagedProcess)`, `loadAll()`, and `markTerminal(UUID, status, exitCode)`.
- Produces: `ProcessSupervisor.recover(ManagedProcess)` returning `ProcessRecovery(RunnerProcessStatus status, RunnerErrorCode errorCode)` with `RUNNING`, the saved terminal state, `UNKNOWN`, or `IDENTITY_MISMATCH` plus `PROCESS_IDENTITY_MISMATCH`.
- Produces: `POST /runner-api/v1/processes/reconcile` accepting `List<RunnerProcessSnapshot>`.

- [ ] **Step 1: Write failing atomic-state and recovery tests**

```java
@Test
void saveUsesAtomicReplacementAndLoadsTheSameIdentity() throws IOException
{
    store.save(process("command-1", 1234L, STARTED_AT));
    assertEquals(process("command-1", 1234L, STARTED_AT), store.loadAll().get(0));
    assertTrue(Files.list(stateDirectory).noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
}

@Test
void pidReuseIsReportedAsIdentityMismatch()
{
    ManagedProcess saved = process("command-1", 1234L, STARTED_AT);
    processLookup.stub(1234L, STARTED_AT.plusSeconds(30), "/usr/bin/java");
    assertEquals(RunnerErrorCode.PROCESS_IDENTITY_MISMATCH, supervisor.recover(saved).errorCode());
    assertFalse(processLookup.wasDestroyed(1234L));
}
```

- [ ] **Step 2: Run recovery tests and confirm failures**

Run: `mvn -f backend/pom.xml -pl ruoyi-train-runner,ruoyi-train-core -am -Dtest=ProcessStateStoreTest,ProcessRecoveryTest,RunnerReconciliationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because state store and reconciliation service are incomplete.

- [ ] **Step 3: Implement fsync-plus-atomic-move state files**

Serialize to `<commandId>.json.tmp`, force the file channel, move with `ATOMIC_MOVE` and `REPLACE_EXISTING`, and fsync the state directory when supported. The final path is `.gputrain/state/<commandId>.json`; stdout and stderr remain under `.gputrain/logs/`.

- [ ] **Step 4: Implement process identity comparison and idempotent stop**

Compare PID, `ProcessHandle.Info.startInstant()`, normalized executable path, and stored argument SHA-256 before treating a live PID as owned. Never destroy a mismatch. Stop sends `destroy()`, waits a bounded duration, then `destroyForcibly()` only for a still-matching owned fixture process. Repeated stop returns the saved terminal state.

- [ ] **Step 5: Reconcile snapshots at Runner startup**

Runner loads local state before polling, recovers every entry, submits one signed reconciliation batch, then starts heartbeat and polling loops. Control plane upserts `train_runner_process` by command ID and only moves command state forward according to Task 1 transition rules.

- [ ] **Step 6: Run all backend tests and commit**

Run: `mvn -f backend/pom.xml clean test`

Expected: all modules build and all tests report zero failures and zero errors.

```bash
git add backend/ruoyi-train-runner backend/ruoyi-train-core
git commit -m "feat: recover runner processes after restart"
```

### Task 8: Package, deploy, perform Linux acceptance, and document evidence

**Files:**
- Create: `deploy/runner/gputrain-runner.service`
- Create: `deploy/runner/runner.properties.example`
- Modify: `.gitignore`
- Modify: `.env.example`
- Modify: `README.md`
- Create: `docs/verification/phase-2-runner.md`
- Modify: `docs/interview/项目面试考点.md`

**Interfaces:**
- Consumes: shaded Runner JAR and control-plane APIs from Tasks 3–7.
- Produces: repeatable `systemd --user` deployment and an evidence-backed phase-two acceptance record.

- [ ] **Step 1: Add packaging and deployment files**

The service runs as the logged-in user, sets `WorkingDirectory=/data/hanw/hw_code/gputrain-test`, executes the absolute Runner JAR path with an absolute properties path, uses `Restart=on-failure`, `RestartSec=5`, `NoNewPrivileges=true`, `PrivateTmp=true`, and no `User=` directive. The example properties contain no credential values.

- [ ] **Step 2: Build all distributable artifacts**

Run:

```bash
mvn -f backend/pom.xml clean package
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env -f deploy/compose.yaml config -q
git diff --check
```

Expected: Maven reactor `BUILD SUCCESS`; frontend tests PASS; Vite build succeeds; Compose and diff checks exit 0.

- [ ] **Step 3: Prepare a non-secret Linux deployment bundle**

Create a tar archive containing only the Runner JAR, service unit, and properties example. Verify it with `tar -tf` and `rg` so it contains no `.env`, key, token, local credential file, MySQL data, Redis data, or IDE metadata.

- [ ] **Step 4: Have the user transfer and install the bundle because SSH keys are not configured**

Provide commands scoped to `/data/hanw/hw_code/gputrain-test` and `~/.config/systemd/user/`. The user creates the secret file with mode `600`; the secret is never pasted into chat or committed. Before starting, verify with:

```bash
stat -c '%a %U %n' /data/hanw/hw_code/gputrain-test/config/runner.secret
systemd-analyze --user verify ~/.config/systemd/user/gputrain-runner.service
```

Expected: mode `600`, owner `hanw`, and no systemd verification errors.

- [ ] **Step 5: Run the real Linux acceptance sequence**

Verify, in order:

1. registration succeeds once and reusing the enrollment returns `ENROLLMENT_ALREADY_USED`;
2. heartbeat makes the Runner online;
3. success fixture exits 0 with one process and expected stdout;
4. failure fixture exits 17 with expected stderr;
5. long fixture remains alive while `systemctl --user restart gputrain-runner` restarts only the Runner;
6. the restarted Runner reports the same PID and no duplicate process;
7. stop request reaches `STOPPED` and repeating stop preserves `STOPPED`;
8. `find /data/hanw/hw_code/gputrain-test -xdev` shows all created files remain under the allowed root.

Capture sanitized command IDs, timestamps, statuses, process counts, and test outputs; never capture credentials or full signatures.

- [ ] **Step 6: Update README, verification evidence, and interview questions**

Document the actual implemented classes and measured results. Add interview answers for outbound Runner architecture, HMAC versus HTTPS, one-time enrollment, command idempotency, avoiding Shell injection, PID reuse, and restart reconciliation. Mark GPU scheduling and real training execution as phase-three work.

- [ ] **Step 7: Run the complete final verification**

Run:

```bash
mvn -f backend/pom.xml clean test
npm --prefix frontend test
npm --prefix frontend run build:prod
docker compose --env-file .env -f deploy/compose.yaml config -q
git diff --check
git status --short
```

Expected: backend, frontend, build, and Compose checks pass; diff check emits no output; status lists only the intended documentation and deployment changes before commit.

- [ ] **Step 8: Commit the verified phase**

```bash
git add .gitignore .env.example README.md deploy/runner docs/verification/phase-2-runner.md docs/interview/项目面试考点.md
git commit -m "docs: record phase two runner verification"
```

After the commit, run `git status --short --branch` and require a clean working tree before declaring phase two complete.

---

## Plan Self-Review Map

- Spec sections 2–3, architecture and slices: Tasks 1–8.
- Spec sections 4–5, enrollment, AES-GCM, HTTPS, HMAC, and replay: Tasks 3–4.
- Spec section 6, four durable records and lease semantics: Tasks 2 and 5.
- Spec section 7, management/Runner APIs and long polling: Tasks 3–5.
- Spec section 8, path/executable/environment/process safety: Task 6.
- Spec section 9, state files and recovery: Task 7.
- Spec section 10, stable errors, retries, and safe logs: Tasks 1, 4, 6, and 7.
- Spec sections 11–12, Linux acceptance and project learning artifacts: Task 8.
