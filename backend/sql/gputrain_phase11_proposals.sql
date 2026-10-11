SET NAMES utf8mb4;

-- 通道 B：命令提案闭环（Agent 命令经人工确认后经白名单 exec 执行）。
-- 四态状态机：PENDING → EXECUTED / REJECTED / FAILED；
-- exitCode ≠ 0 属远端命令合法结果（EXECUTED 承载），传输/鉴权层失败为 FAILED + error_code。
CREATE TABLE IF NOT EXISTS train_command_proposal (
    proposal_id CHAR(36) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    args_json VARCHAR(2048) NOT NULL,
    rendered_command VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    exit_code INT NULL,
    stdout MEDIUMTEXT NULL,
    stderr MEDIUMTEXT NULL,
    error_code VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    executed_at DATETIME(3) NULL,
    PRIMARY KEY (proposal_id),
    KEY idx_train_proposal_actor_time (actor_user_id, created_at),
    KEY idx_train_proposal_server_time (server_id, created_at),
    KEY idx_train_proposal_status (status),
    CONSTRAINT ck_train_proposal_source CHECK (source IN ('MANUAL', 'AGENT')),
    CONSTRAINT ck_train_proposal_status CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
