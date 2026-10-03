SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS train_runner (
    runner_id BIGINT NOT NULL AUTO_INCREMENT,
    runner_name VARCHAR(128) NOT NULL,
    linux_username VARCHAR(128) NOT NULL,
    allowed_root VARCHAR(1024) NOT NULL,
    secret_ciphertext VARCHAR(1024) NOT NULL,
    status VARCHAR(64) NOT NULL,
    last_seen_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (runner_id),
    UNIQUE KEY uk_runner_name (runner_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_runner_enrollment (
    enrollment_id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    runner_name VARCHAR(128) NOT NULL,
    linux_username VARCHAR(128) NOT NULL,
    allowed_root VARCHAR(1024) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (enrollment_id),
    UNIQUE KEY uk_enrollment_digest (token_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_runner_command (
    command_id BIGINT NOT NULL AUTO_INCREMENT,
    command_uuid CHAR(36) NOT NULL,
    runner_id BIGINT NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    payload JSON NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    lease_until DATETIME(3) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    result JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_command_uuid (command_uuid),
    UNIQUE KEY uk_runner_idempotency (runner_id, idempotency_key),
    KEY idx_command_poll (runner_id, status, created_at),
    CONSTRAINT fk_command_runner FOREIGN KEY (runner_id) REFERENCES train_runner (runner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_runner_process (
    process_id BIGINT NOT NULL AUTO_INCREMENT,
    command_id BIGINT NOT NULL,
    runner_id BIGINT NOT NULL,
    pid BIGINT NOT NULL,
    process_started_at DATETIME(3) NOT NULL,
    executable VARCHAR(1024) NOT NULL,
    arguments_sha256 CHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    exit_code INT NULL,
    error_code VARCHAR(64) NULL,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (process_id),
    UNIQUE KEY uk_process_command (command_id),
    KEY idx_process_runner (runner_id),
    CONSTRAINT fk_process_command FOREIGN KEY (command_id) REFERENCES train_runner_command (command_id),
    CONSTRAINT fk_process_runner FOREIGN KEY (runner_id) REFERENCES train_runner (runner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
