# GPUTrain Deployment and Recovery Runbook

This runbook covers the current modular Spring Boot control plane, MySQL/Redis Compose dependencies, and per-user Linux GPU Runner. It describes an operator-controlled rollout; it does not assume a maintenance window or stop training automatically.

## 1. Prerequisites and secrets

- Java 17, Maven 3.9+, Docker Compose, and Node.js for local build checks.
- Production HTTPS in front of the control plane. The `/mcp` endpoint uses the same JWT and `train:job:view` permission as the existing API.
- Keep database credentials, Qwen API keys, runner master key, enrollment pepper, and per-user Runner secret outside Git. Files containing secrets must be owner-only (`0600`). Do not paste secrets into command arguments or demo output.
- The Runner runs as the GPU server's Linux user. Confirm the configured `allowedRoot`, service account, CUDA toolchain, and GPU visibility before a rollout.

## 2. First local deployment

```sh
cp .env.example .env
chmod 600 .env
# Replace every example password before starting.
docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

MySQL initialization scripts run only when the data directory is empty. Compose exposes MySQL and Redis on loopback only. Start the Spring Boot app using the `local` profile and provide `DB_PASSWORD`, `RUNNER_CREDENTIAL_MASTER_KEY`, and `RUNNER_ENROLLMENT_PEPPER` through the process environment. Start the frontend separately for local demo use.

## 3. Pre-upgrade gate

1. Record control-plane and Runner versions, host free space, GPU snapshots, current Runner state, active jobs, active GPU leases, and the owner of every job being considered for maintenance.
2. Prefer idle devices. A shared-device acceptance run is allowed only when the job profile explicitly supports external occupancy, fresh per-device snapshots exceed the configured free-memory threshold, the requested GPU UUID allowlist is explicit, and the operator has authorized the run. Never signal or stop another user's process. Do not restart/stop Runner while its tracked child processes are running. Issue an ordinary STOP only for the acceptance user's jobs, verify final state and PID exit, then confirm its leases are released.
3. Ensure the database backup and JAR/config backups are readable, timestamped, and stored outside the release directory. Record a checksum for each artifact.
4. Check sufficient free disk for the new release, rollback artifacts, logs, and database restore workspace. Do not clean logs, checkpoints, user files, or model caches to create space without owner approval.

## 4. Build and artifact checks

```sh
mvn -f backend/pom.xml clean package
npm --prefix frontend ci
npm --prefix frontend test -- --run
npm --prefix frontend run build:prod
docker compose --env-file .env -f deploy/compose.yaml config -q
```

Record the Git revision and artifact hashes with the release. Do not copy `.env`, API keys, `runner.secret`, or local database files into the bundle.

## 5. Database backup and additive upgrade

Use a MySQL client option file readable only by the operator (`0600`), not a password argument:

```sh
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
mysqldump --defaults-extra-file="$MYSQL_CLIENT_CNF" --single-transaction \
  --routines --triggers --events gputrain | gzip -c > "gputrain.$stamp.sql.gz"
gzip -t "gputrain.$stamp.sql.gz"
shasum -a 256 "gputrain.$stamp.sql.gz"
```

For an existing deployment, apply only the migration files required by the version being installed, in order, with the deployment's MySQL client configuration. The Phase 6 migration is additive and creates the MCP audit table:

```sh
mysql --defaults-extra-file="$MYSQL_CLIENT_CNF" gputrain < backend/sql/gputrain_phase6.sql
```

Phase 3/4/6 SQL files are not automatically replayed on an existing MySQL data directory. Compose's numbered scripts 01–07 apply only to a fresh database. Confirm the new table and indexes before starting the upgraded application.

## 6. Application and Runner rollout

1. Stage the new control-plane JAR, Runner JAR, SQL migration, and non-secret example configs in a versioned release directory.
2. Back up the currently deployed control-plane JAR, Runner JAR, service unit, Runner properties, and secret-file metadata/permissions. Never print/copy secret contents into logs.
3. Migrate the database before enabling code that writes to the new table.
4. Start the control plane using the deployment's existing service manager and profile. Confirm health, login, protected API, and MCP anonymous rejection before changing Runner artifacts.
5. Deploy/restart the Runner only after all its tracked training children are stopped or proven unaffected by its documented `KillMode=process` behavior. Confirm heartbeat and GPU snapshots after restart.
6. Check authenticated MCP initialize, the exact six-tool listing, owner isolation, and an audit row. Only then begin any GPU smoke job, and only on explicitly idle devices.

The 2026-10-07 target inspection found high utilization across all eight GPUs, but GPUs 0, 1, and 5 had at least 107 GiB free each and passed the profile's 70,000 MiB free-memory threshold. A one-step shared-device smoke run completed without interrupting other processes. `/data` remained 95% used (50 GiB available); the tested JAR and database backups fit, but do not remove user files, logs, checkpoints, or model caches to create space. Current deployment and restore evidence is in `docs/verification/phase-7-deployment.md`.

## 7. Rollback and database recovery

- If application health fails after rollout, stop accepting new submissions, preserve logs, and restore the previous JAR/config using the same service manager. Keep the additive Phase 6 audit table; old application code ignores it. Do not roll back by deleting tables.
- If database recovery is necessary, restore the dump into a separate empty database first and validate table counts and application startup against that copy. A live database restore overwrites newer data and requires a separately approved maintenance window.
- Example for a controlled restore into a selected database:

```sh
gzip -dc "$BACKUP_FILE" | mysql --defaults-extra-file="$MYSQL_CLIENT_CNF" "$RESTORE_DATABASE"
```

- After rollback/recovery, verify control-plane health, login, existing jobs, leases, Runner owner binding, process reconciliation, and latest GPU snapshots. Never release a lease solely because it is old; reconcile the actual process first.

## 8. Routine backup and restore rehearsal

- Back up MySQL using the command in §5 and preserve the exact application JAR, schema version, sanitized configuration template, and checksums together.
- Keep backups encrypted and outside the GPU data volume. Retention and off-host replication are deployment-owner settings; this repository does not configure them.
- Rehearse restores into an isolated database/host before relying on them. This phase's local build does not constitute a restore drill.

## 9. Read-only demo

Use `scripts/demo-phase7-mcp.sh` with an existing JWT for a user who has `train:overview:view` and `train:job:view`. The script reads the overview and calls only the MCP initialize, tool listing, and `list_my_experiments` operations. It never submits, stops, or modifies a training job.
