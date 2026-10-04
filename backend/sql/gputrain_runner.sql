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
    token_ciphertext VARCHAR(1024) NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (enrollment_id),
    UNIQUE KEY uk_enrollment_digest (token_digest),
    UNIQUE KEY uk_enrollment_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upgrade intermediate phase-two schemas in place. Each DDL step is guarded so
-- this same file remains safe for fresh initialization and repeated manual runs.
SET @runner_enrollment_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'train_runner_enrollment'
       AND COLUMN_NAME = 'token_ciphertext') = 0,
    'ALTER TABLE train_runner_enrollment ADD COLUMN token_ciphertext VARCHAR(1024) NULL AFTER token_digest',
    'SELECT 1'
);
PREPARE runner_enrollment_stmt FROM @runner_enrollment_ddl;
EXECUTE runner_enrollment_stmt;
DEALLOCATE PREPARE runner_enrollment_stmt;

SET @runner_enrollment_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'train_runner_enrollment'
       AND COLUMN_NAME = 'idempotency_key') = 0,
    'ALTER TABLE train_runner_enrollment ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER token_ciphertext',
    'SELECT 1'
);
PREPARE runner_enrollment_stmt FROM @runner_enrollment_ddl;
EXECUTE runner_enrollment_stmt;
DEALLOCATE PREPARE runner_enrollment_stmt;

-- Legacy rows have no caller key. Enrollment ID makes generated keys unique;
-- UUID further separates this reserved migration namespace from caller keys.
UPDATE train_runner_enrollment
SET idempotency_key = CONCAT('legacy-', enrollment_id, '-', REPLACE(UUID(), '-', ''))
WHERE idempotency_key IS NULL OR TRIM(idempotency_key) = '';

SET @runner_enrollment_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'train_runner_enrollment'
       AND COLUMN_NAME = 'idempotency_key'
       AND (IS_NULLABLE = 'YES' OR COLLATION_NAME <> 'utf8mb4_0900_bin'
            OR CHARACTER_MAXIMUM_LENGTH <> 128)) > 0,
    'ALTER TABLE train_runner_enrollment MODIFY COLUMN idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL',
    'SELECT 1'
);
PREPARE runner_enrollment_stmt FROM @runner_enrollment_ddl;
EXECUTE runner_enrollment_stmt;
DEALLOCATE PREPARE runner_enrollment_stmt;

SET @runner_enrollment_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'train_runner_enrollment'
       AND INDEX_NAME = 'uk_enrollment_idempotency'
       AND NON_UNIQUE = 0
       AND COLUMN_NAME = 'idempotency_key') = 0,
    'ALTER TABLE train_runner_enrollment ADD UNIQUE KEY uk_enrollment_idempotency (idempotency_key)',
    'SELECT 1'
);
PREPARE runner_enrollment_stmt FROM @runner_enrollment_ddl;
EXECUTE runner_enrollment_stmt;
DEALLOCATE PREPARE runner_enrollment_stmt;

CREATE TABLE IF NOT EXISTS train_runner_command (
    command_id BIGINT NOT NULL AUTO_INCREMENT,
    command_uuid CHAR(36) NOT NULL,
    runner_id BIGINT NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    payload JSON NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
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

SET @runner_command_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'train_runner_command'
       AND COLUMN_NAME = 'idempotency_key'
       AND (IS_NULLABLE = 'YES' OR COLLATION_NAME <> 'utf8mb4_0900_bin'
            OR CHARACTER_MAXIMUM_LENGTH <> 128)) > 0,
    'ALTER TABLE train_runner_command MODIFY COLUMN idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL',
    'SELECT 1'
);
PREPARE runner_command_stmt FROM @runner_command_ddl;
EXECUTE runner_command_stmt;
DEALLOCATE PREPARE runner_command_stmt;

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

-- Runner permissions are hidden under the existing GPUTrain directory.
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT permission.menu_name, parent.menu_id, permission.order_num, '#', NULL, '', '', 1, 0,
       'F', '1', '0', permission.perms, '#', 'admin', NOW(), 'Runner management permission'
FROM (
    SELECT 'Runner enrollment' AS menu_name, 1 AS order_num, 'train:runner:enroll' AS perms
    UNION ALL SELECT 'Runner view', 2, 'train:runner:view'
    UNION ALL SELECT 'Runner command', 3, 'train:runner:command'
) permission
JOIN sys_menu parent ON parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu child WHERE child.parent_id = parent.menu_id
      AND child.perms = permission.perms
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id
FROM sys_menu menu
JOIN sys_menu parent ON parent.menu_id = menu.parent_id
WHERE parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
  AND menu.perms IN ('train:runner:enroll', 'train:runner:view', 'train:runner:command')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu role_menu
      WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id
  );
