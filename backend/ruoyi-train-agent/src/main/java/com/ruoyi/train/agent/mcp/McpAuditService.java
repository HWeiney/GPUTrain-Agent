package com.ruoyi.train.agent.mcp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stores only safe audit metadata; MCP arguments and result bodies are intentionally excluded. */
@Service
public class McpAuditService
{
    private final JdbcTemplate jdbc;

    public McpAuditService(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    public void record(long actorUserId, String tool, Long jobId, String outcome, String errorCode)
    {
        jdbc.update("INSERT INTO train_mcp_audit(actor_user_id, tool_name, job_id, outcome, error_code, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                actorUserId, tool, jobId, outcome, errorCode, Timestamp.from(Instant.now()));
    }
}
