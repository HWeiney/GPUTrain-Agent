SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS train_mcp_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    job_id BIGINT NULL,
    outcome VARCHAR(16) NOT NULL,
    error_code VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (audit_id),
    KEY idx_train_mcp_audit_actor_time (actor_user_id, created_at),
    KEY idx_train_mcp_audit_job_time (job_id, created_at),
    CONSTRAINT ck_train_mcp_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
