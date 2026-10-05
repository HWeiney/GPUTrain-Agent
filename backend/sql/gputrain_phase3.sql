SET NAMES utf8mb4;

-- Canonical physical GPU device keyed by NVIDIA GPU UUID.
CREATE TABLE IF NOT EXISTS train_gpu_device (
    device_id BIGINT NOT NULL AUTO_INCREMENT,
    gpu_uuid VARCHAR(128) NOT NULL,
    host_signature VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    identity_state VARCHAR(64) NOT NULL,
    schedulable TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (device_id),
    UNIQUE KEY uk_gpu_device_uuid (gpu_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Raw observed snapshots (observable facts), never authoritative for "free".
CREATE TABLE IF NOT EXISTS train_gpu_snapshot (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    runner_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(128) NOT NULL,
    visible_index INT NOT NULL,
    total_memory_mib BIGINT NOT NULL,
    used_memory_mib BIGINT NOT NULL,
    utilization_percent INT NOT NULL,
    mig_mode VARCHAR(64) NOT NULL,
    sampled_at DATETIME(3) NOT NULL,
    compute_processes JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    KEY idx_snapshot_runner_gpu (runner_id, gpu_uuid, sampled_at),
    KEY idx_snapshot_sampled (sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Training job aggregate and its state machine.
CREATE TABLE IF NOT EXISTS train_job (
    job_id BIGINT NOT NULL AUTO_INCREMENT,
    job_uuid CHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    runner_id BIGINT NULL,
    gpu_count INT NOT NULL,
    min_memory_mib BIGINT NOT NULL,
    allowed_gpu_uuids JSON NULL,
    denied_gpu_uuids JSON NULL,
    process_spec_json JSON NULL,
    status VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    start_command_id BIGINT NULL,
    stop_command_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_job_uuid (job_uuid),
    UNIQUE KEY uk_job_request_key (request_key),
    KEY idx_job_status (status),
    KEY idx_job_runner (runner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- GPU leases. A generated column yields a database-level unique constraint on
-- at most one ACTIVE lease per GPU; RELEASED/EXPIRED rows produce NULL
-- (multiple NULLs allowed), so the card may be leased again after release.
CREATE TABLE IF NOT EXISTS train_gpu_lease (
    lease_id BIGINT NOT NULL AUTO_INCREMENT,
    lease_uuid CHAR(36) NOT NULL,
    job_id BIGINT NOT NULL,
    runner_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquired_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    renewed_at DATETIME(3) NULL,
    released_at DATETIME(3) NULL,
    attempt INT NOT NULL DEFAULT 0,
    active_gpu_uuid VARCHAR(128) GENERATED ALWAYS AS
        (CASE WHEN status = 'ACTIVE' THEN gpu_uuid ELSE NULL END) VIRTUAL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (lease_id),
    UNIQUE KEY uk_lease_uuid (lease_uuid),
    UNIQUE KEY uk_active_gpu (active_gpu_uuid),
    UNIQUE KEY uk_job_lease_idem (job_id, idempotency_key),
    KEY idx_lease_status (status),
    KEY idx_lease_runner (runner_id),
    KEY idx_lease_expires (expires_at),
    CONSTRAINT fk_lease_job FOREIGN KEY (job_id) REFERENCES train_job (job_id),
    CONSTRAINT fk_lease_runner FOREIGN KEY (runner_id) REFERENCES train_runner (runner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Audit trail for scheduling decisions. Never stores secrets.
CREATE TABLE IF NOT EXISTS train_gpu_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(3) NOT NULL,
    actor_user_id BIGINT NULL,
    job_id BIGINT NULL,
    runner_id BIGINT NULL,
    gpu_uuid VARCHAR(128) NULL,
    lease_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NULL,
    detail VARCHAR(1024) NULL,
    PRIMARY KEY (audit_id),
    KEY idx_audit_job (job_id),
    KEY idx_audit_runner (runner_id),
    KEY idx_audit_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotent migration: add process_spec_json to deployments that applied an earlier revision.
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE train_job ADD COLUMN process_spec_json JSON NULL AFTER denied_gpu_uuids',
        'SELECT 1')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'train_job'
      AND COLUMN_NAME = 'process_spec_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Phase-three permissions under the existing GPUTrain directory.
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT permission.menu_name, parent.menu_id, permission.order_num, '#', NULL, '', '', 1, 0,
       'F', '1', '0', permission.perms, '#', 'admin', NOW(), 'GPUTrain scheduling permission'
FROM (
    SELECT 'GPU view' AS menu_name, 4 AS order_num, 'train:gpu:view' AS perms
    UNION ALL SELECT 'Job view', 5, 'train:job:view'
    UNION ALL SELECT 'Job manage', 6, 'train:job:manage'
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
  AND menu.perms IN ('train:gpu:view', 'train:job:view', 'train:job:manage')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu role_menu
      WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id
  );
