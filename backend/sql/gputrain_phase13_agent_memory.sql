SET NAMES utf8mb4;

-- P4.5 Agent 会话长记忆：append-only 交互轮次日志（意图 → 动作 → 依据 → 提案链接）。
-- 结果（status/exit_code/输出摘要）查询时 JOIN train_command_proposal 获得，记忆行落库后不更新。
CREATE TABLE IF NOT EXISTS train_agent_memory (
    memory_id CHAR(36) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    intent_text VARCHAR(4000) NOT NULL,
    action VARCHAR(64) NOT NULL,
    args_json VARCHAR(2048) NOT NULL,
    rationale VARCHAR(1024) NOT NULL,
    proposal_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (memory_id),
    KEY idx_agent_memory_actor_server_time (actor_user_id, server_id, created_at),
    KEY idx_agent_memory_proposal (proposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
