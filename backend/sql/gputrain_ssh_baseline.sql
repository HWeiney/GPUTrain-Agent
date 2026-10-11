SET NAMES utf8mb4;

-- Fresh installations start with SSH jobs. Nullable legacy identifiers are retained
-- only because the same result mapper reads pre-migration historical databases.
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
    KEY idx_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_job_alert (
    alert_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    rule_code VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    message VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (alert_id),
    UNIQUE KEY uk_train_job_alert_rule (job_id, rule_code),
    KEY idx_train_job_alert_history (job_id, alert_id),
    CONSTRAINT fk_train_job_alert_job FOREIGN KEY (job_id) REFERENCES train_job(job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'Job manage', parent.menu_id, 6, '#', NULL, '', '', 1, 0,
       'F', '1', '0', 'train:job:manage', '#', 'admin', NOW(), 'GPUTrain job permission'
FROM sys_menu parent
WHERE parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:job:manage');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id FROM sys_menu menu
WHERE menu.perms = 'train:job:manage'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu role_menu
                  WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id);
