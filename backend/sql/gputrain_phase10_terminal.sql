SET NAMES utf8mb4;

-- Phase 10: Web 终端会话审计。会话由应用侧生成 UUID（session_uuid 为主键），
-- 记录打开/错误/关闭三态，与 train_mcp_audit 的三态模型一致；可重复执行。

CREATE TABLE IF NOT EXISTS train_terminal_session (
    session_uuid CHAR(36) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    close_reason VARCHAR(32) NULL,
    error_code VARCHAR(32) NULL,
    opened_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3) NULL,
    PRIMARY KEY (session_uuid),
    KEY idx_train_terminal_actor_time (actor_user_id, opened_at),
    KEY idx_train_terminal_server_time (server_id, opened_at),
    CONSTRAINT ck_train_terminal_status CHECK (status IN ('ACTIVE', 'CLOSED', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
