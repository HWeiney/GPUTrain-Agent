SET NAMES utf8mb4;

-- P4：Agent 提案依据列（展示与审计）。幂等：列已存在则跳过。
SET @rationale_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'train_command_proposal' AND COLUMN_NAME = 'rationale');
SET @ddl = IF(@rationale_exists = 0,
    'ALTER TABLE train_command_proposal ADD COLUMN rationale VARCHAR(1024) NULL AFTER source',
    'SELECT ''rationale column already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
