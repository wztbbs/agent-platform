-- 已有 ask_trace / trace_event 不需要改名或复制数据。
-- 停服升级时仅执行一次；新库使用 platform/db/schema.sql，无须重放此脚本。
ALTER TABLE ask_trace ADD COLUMN request_started_at DATETIME(6) NULL;
ALTER TABLE ask_trace ADD COLUMN upstream_trace_id VARCHAR(128) NULL;
