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

-- 通用执行 Trace 与事件；表名保留以保持已有回放接口和数据。
-- 一次问答 = 一条 ask_trace（就是 Trace 的主表，👎 之后靠它完整回放）
CREATE TABLE IF NOT EXISTS ask_trace (
    trace_id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(64)   NULL,
    question          TEXT          NOT NULL,
    intent            VARCHAR(64)   NULL, -- 历史意图标签，仅兼容旧数据；当前流程不再写入
    visitor_id        VARCHAR(64)   NULL, -- 访客标识（cexpilot_uid cookie）
    is_real_user      BOOLEAN       NULL DEFAULT TRUE, -- true=用户流量，false=脚本标记，NULL=历史来源未知
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    answer            MEDIUMTEXT    NULL,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    build_commit      VARCHAR(64)   NULL, -- 构建时打入的 git commit 指纹（定位答案对应的代码版本）
    llm_steps         INT           NOT NULL DEFAULT 0,
    tool_calls        INT           NOT NULL DEFAULT 0,
    reused_facts      INT           NOT NULL DEFAULT 0, -- 本轮复用的历史事实数（阶段二 FACT_REUSED 计数）
    planner_llm_calls INT           NOT NULL DEFAULT 0, -- planner 阶段 LLM 调用次数（含修复重试）
    router_llm_calls INT            NOT NULL DEFAULT 0,
    agent_type       VARCHAR(16)    NULL,
    answer_llm_calls  INT           NOT NULL DEFAULT 0, -- answer 阶段 LLM 调用次数（0 或 1）
    metric_fetch_count INT          NOT NULL DEFAULT 0, -- metric 节点真实取数次数（REUSE_FACT 不算）
    calculation_count INT           NOT NULL DEFAULT 0, -- 算子节点执行次数
    http_request_count INT          NOT NULL DEFAULT 0, -- 真实 HTTP 请求数（分页/重试逐次计）
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    cost              DECIMAL(12,8) NULL,
    duration_ms       BIGINT        NULL,
    error             TEXT          NULL,
    request_started_at DATETIME(6) NULL, -- UTC 请求开始时间，恢复沿用原值
    upstream_trace_id VARCHAR(128) NULL, -- 可选的上游链路关联
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME      NULL,
    KEY idx_trace_conversation (conversation_id, created_at),
    KEY idx_trace_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每次 LLM 调用 / Tool 调用 = 一条 trace_event
CREATE TABLE IF NOT EXISTS trace_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id          VARCHAR(64) NOT NULL,
    seq               INT         NOT NULL,
    event_type        VARCHAR(32) NOT NULL, -- LLM_CALL / TOOL_CALL / PLAN / METRIC_RESULT / EVIDENCE_REUSE_DECISION / FACT_REUSED 等
    name              VARCHAR(64) NULL,     -- tool 名或 llm
    input_json        JSON        NULL,
    output_json       JSON        NULL,
    duration_ms       BIGINT      NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    ttft_ms           BIGINT      NULL, -- 首 token 时间（prefill 耗时），仅 LLM_CALL 流式路径有值
    cached_tokens     INT         NULL, -- prompt 缓存命中量，仅 LLM_CALL 有值
    error             TEXT        NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_trace (trace_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
