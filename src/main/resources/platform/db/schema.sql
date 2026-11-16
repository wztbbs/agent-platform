-- 公共底座持久化运行表；由宿主应用显式执行，库本身不自动建表。
CREATE TABLE IF NOT EXISTS platform_run (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    payload_text MEDIUMTEXT NOT NULL,
    KEY idx_platform_run_due (next_attempt_at, lease_until),
    KEY idx_platform_run_owner (owner_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 底座工具调用：身份与调度索引为列，执行检查点为 JSON 文本。
CREATE TABLE IF NOT EXISTS platform_tool_call (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    payload_text MEDIUMTEXT NOT NULL,
    KEY idx_platform_tool_call_due (next_attempt_at, lease_until),
    KEY idx_platform_tool_call_owner (owner_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS platform_run_checkpoint (
    run_id VARCHAR(64) NOT NULL,
    checkpoint_key VARCHAR(160) NOT NULL,
    payload_text MEDIUMTEXT NOT NULL,
    PRIMARY KEY (run_id, checkpoint_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 底座审批方案：审批状态独立于 payload 中的执行状态，方案内容和指纹不可原地修改。
CREATE TABLE IF NOT EXISTS platform_prepared_plan (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    payload_text MEDIUMTEXT NOT NULL,
    KEY idx_platform_prepared_plan_due (next_attempt_at, lease_until),
    KEY idx_platform_prepared_plan_owner (owner_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
