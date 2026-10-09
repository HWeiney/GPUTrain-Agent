SET NAMES utf8mb4;

-- Reuses train_server_gpu_device and train_server_gpu_snapshot from Phase 8.

-- Extend the Runner-era aggregate additively. Conditional DDL allows safe replays
-- on databases where the migration was only partially applied.
SELECT GROUP_CONCAT(fragment SEPARATOR ', ')
INTO @phase9_job_columns
FROM (
    SELECT 'ADD COLUMN execution_mode VARCHAR(24) NULL DEFAULT ''RUNNER_LEGACY''' AS fragment
      WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='train_job' AND column_name='execution_mode')
    UNION ALL SELECT 'ADD COLUMN server_id BIGINT NULL'
      WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='train_job' AND column_name='server_id')
    UNION ALL SELECT 'ADD COLUMN account_id BIGINT NULL'
      WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='train_job' AND column_name='account_id')
    UNION ALL SELECT 'ADD COLUMN remote_unit_name VARCHAR(160) NULL'
      WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='train_job' AND column_name='remote_unit_name')
    UNION ALL SELECT 'ADD COLUMN remote_working_directory VARCHAR(1024) NULL'
      WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='train_job' AND column_name='remote_working_directory')
) AS missing_columns;
SET @phase9_job_ddl = IF(@phase9_job_columns IS NULL, 'SELECT 1', CONCAT('ALTER TABLE train_job ', @phase9_job_columns));
PREPARE phase9_job_stmt FROM @phase9_job_ddl;
EXECUTE phase9_job_stmt;
DEALLOCATE PREPARE phase9_job_stmt;

UPDATE train_job SET execution_mode='RUNNER_LEGACY' WHERE execution_mode IS NULL;

SET @phase9_fk_server = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='train_job' AND constraint_name='fk_train_job_server') = 0,
    'ALTER TABLE train_job ADD CONSTRAINT fk_train_job_server FOREIGN KEY (server_id) REFERENCES train_server(server_id)', 'SELECT 1');
PREPARE phase9_fk_stmt FROM @phase9_fk_server;
EXECUTE phase9_fk_stmt;
DEALLOCATE PREPARE phase9_fk_stmt;

SET @phase9_fk_account = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='train_job' AND constraint_name='fk_train_job_server_account') = 0,
    'ALTER TABLE train_job ADD CONSTRAINT fk_train_job_server_account FOREIGN KEY (server_id,account_id) REFERENCES train_server_account(server_id,account_id)', 'SELECT 1');
PREPARE phase9_fk_stmt FROM @phase9_fk_account;
EXECUTE phase9_fk_stmt;
DEALLOCATE PREPARE phase9_fk_stmt;

CREATE TABLE IF NOT EXISTS train_server_gpu_lease (
    lease_id BIGINT NOT NULL AUTO_INCREMENT,
    lease_uuid CHAR(36) NOT NULL,
    job_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquired_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    renewed_at DATETIME(3) NULL,
    released_at DATETIME(3) NULL,
    attempt INT NOT NULL DEFAULT 0,
    active_server_gpu VARCHAR(160) GENERATED ALWAYS AS
        (CASE WHEN status IN ('ACTIVE','QUARANTINED') THEN CONCAT(server_id,':',gpu_uuid) ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (lease_id),
    UNIQUE KEY uk_ssh_lease_uuid (lease_uuid),
    UNIQUE KEY uk_active_server_gpu (active_server_gpu),
    UNIQUE KEY uk_ssh_job_lease_idem (job_id,idempotency_key),
    KEY idx_ssh_lease_job (job_id,status),
    KEY idx_ssh_lease_expiry (status,expires_at),
    CONSTRAINT fk_ssh_lease_job FOREIGN KEY (job_id) REFERENCES train_job(job_id),
    CONSTRAINT fk_ssh_lease_account FOREIGN KEY (server_id,account_id) REFERENCES train_server_account(server_id,account_id),
    CONSTRAINT fk_ssh_lease_device FOREIGN KEY (server_id,gpu_uuid) REFERENCES train_server_gpu_device(server_id,gpu_uuid),
    CONSTRAINT ck_ssh_lease_status CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','QUARANTINED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_ssh_job_log (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    stream VARCHAR(8) NOT NULL,
    byte_offset BIGINT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_ssh_job_log_offset (job_id,stream,byte_offset),
    KEY idx_ssh_job_log_replay (job_id,event_id),
    CONSTRAINT fk_ssh_job_log_job FOREIGN KEY (job_id) REFERENCES train_job(job_id),
    CONSTRAINT ck_ssh_job_log_stream CHECK (stream IN ('STDOUT','STDERR')),
    CONSTRAINT ck_ssh_job_log_chunk_size CHECK (OCTET_LENGTH(content) <= 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_ssh_job_metric (
    metric_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    metric_name VARCHAR(64) NOT NULL,
    metric_value DOUBLE NOT NULL,
    step_number BIGINT NULL,
    sampled_at DATETIME(3) NOT NULL,
    PRIMARY KEY (metric_id),
    UNIQUE KEY uk_ssh_job_metric_event (event_id,metric_name),
    KEY idx_ssh_job_metric_history (job_id,metric_id),
    CONSTRAINT fk_ssh_job_metric_job FOREIGN KEY (job_id) REFERENCES train_job(job_id),
    CONSTRAINT fk_ssh_job_metric_event FOREIGN KEY (event_id) REFERENCES train_ssh_job_log(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
