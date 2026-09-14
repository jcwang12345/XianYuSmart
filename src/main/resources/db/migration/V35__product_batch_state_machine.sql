-- PRD-01~04：商品版本证据、批量任务完整状态机、恢复/取消/通知关联。

ALTER TABLE xianyu_goods
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER coverage_status,
    ADD COLUMN support_policy VARCHAR(1000) NULL AFTER condition_code,
    ADD COLUMN location_text VARCHAR(255) NULL AFTER support_policy,
    ADD KEY idx_goods_version (tenant_id, xianyu_account_id, xy_good_id, row_version);

ALTER TABLE xianyu_goods_event
    ADD COLUMN batch_job_id BIGINT NULL AFTER idempotency_key,
    ADD COLUMN batch_item_id BIGINT NULL AFTER batch_job_id,
    ADD COLUMN platform_request_id VARCHAR(100) NULL AFTER batch_item_id,
    ADD KEY idx_goods_event_batch (tenant_id, batch_job_id, batch_item_id);

ALTER TABLE xianyu_goods_batch_job
    ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER request_id,
    ADD COLUMN filter_snapshot_hash CHAR(64) NULL AFTER selection_query_json,
    ADD COLUMN skipped_count INT NOT NULL DEFAULT 0 AFTER failed_count,
    ADD COLUMN cancelled_count INT NOT NULL DEFAULT 0 AFTER unknown_count,
    ADD COLUMN progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER cancelled_count,
    ADD COLUMN cancel_requested_time DATETIME(3) NULL AFTER operator_username,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER cancel_requested_time,
    ADD COLUMN recovery_count INT NOT NULL DEFAULT 0 AFTER cancellation_reason,
    ADD COLUMN last_dispatch_time DATETIME(3) NULL AFTER recovery_count,
    ADD COLUMN notification_sent TINYINT NOT NULL DEFAULT 0 AFTER last_dispatch_time,
    ADD KEY idx_goods_batch_recovery (status, last_dispatch_time);

UPDATE xianyu_goods_batch_job SET idempotency_key=request_id WHERE idempotency_key IS NULL OR idempotency_key='';
UPDATE xianyu_goods_batch_job SET status='PARTIAL_SUCCESS' WHERE status='PARTIAL';

ALTER TABLE xianyu_goods_batch_job
    MODIFY COLUMN idempotency_key VARCHAR(100) NOT NULL,
    ADD UNIQUE KEY uk_goods_batch_idempotency (tenant_id, idempotency_key);

ALTER TABLE xianyu_goods_batch_item
    ADD COLUMN expected_goods_version BIGINT NULL AFTER operation_type,
    ADD COLUMN old_value_json LONGTEXT NULL AFTER expected_goods_version,
    ADD COLUMN new_value_json LONGTEXT NULL AFTER old_value_json,
    ADD COLUMN platform_request_id VARCHAR(100) NULL AFTER outcome_state,
    ADD COLUMN error_code VARCHAR(100) NULL AFTER platform_response_code,
    ADD COLUMN claimed_by VARCHAR(80) NULL AFTER error_message,
    ADD COLUMN claimed_time DATETIME(3) NULL AFTER claimed_by,
    ADD COLUMN cancelled_time DATETIME(3) NULL AFTER completed_time,
    ADD KEY idx_goods_batch_item_job_status (tenant_id, batch_job_id, status, id);

CREATE TABLE xianyu_goods_batch_rate_limit (
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    next_allowed_time DATETIME(3) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, xianyu_account_id),
    KEY idx_goods_batch_rate_due (next_allowed_time),
    CONSTRAINT fk_goods_batch_rate_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
