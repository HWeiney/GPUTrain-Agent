SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS train_log_cursor (
    command_id BIGINT NOT NULL,
    stream VARCHAR(8) NOT NULL,
    next_offset BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (command_id, stream),
    CONSTRAINT fk_train_log_cursor_command FOREIGN KEY (command_id) REFERENCES train_runner_command(command_id),
    CONSTRAINT ck_train_log_cursor_stream CHECK (stream IN ('STDOUT', 'STDERR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_job_log (
    log_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    command_id BIGINT NOT NULL,
    stream VARCHAR(8) NOT NULL,
    byte_offset BIGINT NOT NULL,
    content VARBINARY(32768) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (log_id),
    UNIQUE KEY uk_train_job_log_offset (command_id, stream, byte_offset),
    KEY idx_train_job_log_replay (job_id, log_id),
    CONSTRAINT fk_train_job_log_job FOREIGN KEY (job_id) REFERENCES train_job(job_id),
    CONSTRAINT fk_train_job_log_command FOREIGN KEY (command_id) REFERENCES train_runner_command(command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS train_job_metric (
    metric_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    log_id BIGINT NOT NULL,
    metric_name VARCHAR(64) NOT NULL,
    metric_value DOUBLE NOT NULL,
    step_number BIGINT NULL,
    sampled_at DATETIME(3) NOT NULL,
    PRIMARY KEY (metric_id),
    UNIQUE KEY uk_train_job_metric_log (log_id, metric_name),
    KEY idx_train_job_metric_history (job_id, metric_id),
    CONSTRAINT fk_train_job_metric_job FOREIGN KEY (job_id) REFERENCES train_job(job_id),
    CONSTRAINT fk_train_job_metric_log FOREIGN KEY (log_id) REFERENCES train_job_log(log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
