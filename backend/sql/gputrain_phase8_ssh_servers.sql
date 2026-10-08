SET NAMES utf8mb4;

-- Additive initialization; rerun this file manually for an existing database.
CREATE TABLE IF NOT EXISTS train_server (
    server_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 22,
    host_key_sha256 VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (server_id),
    UNIQUE KEY uk_server_host_port (host,port),
    CONSTRAINT ck_server_port CHECK (port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_server_account (
    account_id BIGINT NOT NULL AUTO_INCREMENT,
    server_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    linux_username VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    auth_type VARCHAR(32) NOT NULL,
    credential_ciphertext TEXT NOT NULL,
    passphrase_ciphertext VARCHAR(2048) NULL,
    has_credential TINYINT(1) GENERATED ALWAYS AS (CHAR_LENGTH(credential_ciphertext) > 0) STORED,
    allowed_root VARCHAR(1024) NOT NULL,
    profile_json JSON NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    error_code VARCHAR(64) NULL,
    last_probe_at DATETIME(3) NULL,
    last_success_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (account_id),
    UNIQUE KEY uk_server_account (server_id,owner_user_id,linux_username),
    UNIQUE KEY uk_server_account_identity (server_id,account_id),
    KEY idx_server_account_owner (owner_user_id,server_id),
    CONSTRAINT fk_server_account_server FOREIGN KEY (server_id) REFERENCES train_server (server_id),
    CONSTRAINT ck_server_account_auth CHECK (auth_type IN ('PASSWORD', 'PRIVATE_KEY')),
    CONSTRAINT ck_server_account_status CHECK (status IN ('ONLINE', 'DEGRADED', 'OFFLINE', 'HOST_KEY_CHANGED', 'AUTH_FAILED', 'UNKNOWN')),
    CONSTRAINT ck_server_account_profile_size CHECK (profile_json IS NULL OR OCTET_LENGTH(CAST(profile_json AS CHAR)) <= 16384)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_server_snapshot (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    server_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    sampled_at DATETIME(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    capabilities_json JSON NULL,
    facts_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    KEY idx_server_snapshot (server_id,account_id,sampled_at),
    CONSTRAINT fk_server_snapshot_account FOREIGN KEY (server_id,account_id) REFERENCES train_server_account (server_id,account_id),
    CONSTRAINT ck_server_snapshot_status CHECK (status IN ('ONLINE', 'DEGRADED', 'OFFLINE', 'HOST_KEY_CHANGED', 'AUTH_FAILED', 'UNKNOWN')),
    CONSTRAINT ck_server_snapshot_facts_size CHECK (OCTET_LENGTH(CAST(facts_json AS CHAR)) <= 65536),
    CONSTRAINT ck_server_snapshot_capabilities_size CHECK (capabilities_json IS NULL OR OCTET_LENGTH(CAST(capabilities_json AS CHAR)) <= 4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_server_gpu_device (
    device_id BIGINT NOT NULL AUTO_INCREMENT,
    server_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    identity_state VARCHAR(32) NOT NULL DEFAULT 'OBSERVED',
    schedulable TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (device_id),
    UNIQUE KEY uk_server_gpu (server_id,gpu_uuid),
    CONSTRAINT fk_server_gpu_device_server FOREIGN KEY (server_id) REFERENCES train_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_server_gpu_snapshot (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    server_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    visible_index INT NOT NULL,
    total_memory_mib BIGINT NOT NULL,
    used_memory_mib BIGINT NOT NULL,
    utilization_percent INT NOT NULL,
    mig_mode VARCHAR(32) NOT NULL,
    driver_version VARCHAR(64) NULL,
    compute_processes JSON NULL,
    sampled_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    KEY idx_server_gpu_snapshot (server_id,account_id,gpu_uuid,sampled_at),
    CONSTRAINT fk_server_gpu_snapshot_account FOREIGN KEY (server_id,account_id) REFERENCES train_server_account (server_id,account_id),
    CONSTRAINT fk_server_gpu_snapshot_device FOREIGN KEY (server_id,gpu_uuid) REFERENCES train_server_gpu_device (server_id,gpu_uuid),
    CONSTRAINT ck_server_gpu_snapshot_process_size CHECK (compute_processes IS NULL OR OCTET_LENGTH(CAST(compute_processes AS CHAR)) <= 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
