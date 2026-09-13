-- ORD-01~04：订单真值、物流、退款快照与不可逆动作审计。

ALTER TABLE xianyu_goods_order
    ADD COLUMN order_amount DECIMAL(12,2) NULL AFTER total_price,
    ADD COLUMN currency_code CHAR(3) NOT NULL DEFAULT 'CNY' AFTER order_amount,
    ADD COLUMN order_status_code VARCHAR(40) NULL AFTER platform_trade_status,
    ADD COLUMN refund_status VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER order_status_code,
    ADD COLUMN refund_amount DECIMAL(12,2) NULL AFTER refund_status,
    ADD COLUMN order_flag VARCHAR(16) NULL AFTER refund_amount,
    ADD COLUMN order_note VARCHAR(1000) NULL AFTER order_flag,
    ADD COLUMN shipment_type VARCHAR(24) NULL AFTER order_note,
    ADD COLUMN logistics_company_code VARCHAR(64) NULL AFTER shipment_type,
    ADD COLUMN logistics_company_name VARCHAR(128) NULL AFTER logistics_company_code,
    ADD COLUMN tracking_number VARCHAR(128) NULL AFTER logistics_company_name,
    ADD COLUMN logistics_status VARCHAR(40) NULL AFTER tracking_number,
    ADD COLUMN logistics_last_event VARCHAR(500) NULL AFTER logistics_status,
    ADD COLUMN logistics_updated_time DATETIME(3) NULL AFTER logistics_last_event,
    ADD COLUMN data_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_EVENT' AFTER logistics_updated_time,
    ADD COLUMN sync_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL' AFTER data_source,
    ADD COLUMN coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL' AFTER sync_status,
    ADD COLUMN last_synced_time DATETIME(3) NULL AFTER coverage_status,
    ADD COLUMN last_sync_request_id VARCHAR(80) NULL AFTER last_synced_time,
    ADD KEY idx_order_matrix (tenant_id, xianyu_account_id, refund_status, delivery_status, create_time),
    ADD KEY idx_order_tracking (tenant_id, tracking_number),
    ADD KEY idx_order_amount (tenant_id, order_amount, create_time);

-- 只迁移可确定为十进制数的历史金额；不可解析的金额保持 NULL，不能冒充 0。
UPDATE xianyu_goods_order
   SET order_amount = CAST(total_price AS DECIMAL(12,2))
 WHERE total_price REGEXP '^[0-9]+(\\.[0-9]{1,2})?$';

CREATE TABLE xianyu_order_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_record_id BIGINT NOT NULL,
    order_id VARCHAR(128) NULL,
    event_type VARCHAR(48) NOT NULL,
    event_origin VARCHAR(32) NOT NULL,
    outcome_state VARCHAR(40) NOT NULL,
    data_source VARCHAR(32) NOT NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    request_id VARCHAR(80) NULL,
    idempotency_key VARCHAR(100) NULL,
    before_json LONGTEXT NULL,
    after_json LONGTEXT NULL,
    field_diff_json LONGTEXT NULL,
    platform_response_code VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_event_request (tenant_id, event_type, request_id),
    KEY idx_order_event_timeline (tenant_id, order_record_id, created_time),
    CONSTRAINT fk_order_event_order FOREIGN KEY (order_record_id) REFERENCES xianyu_goods_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_event_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_event_user FOREIGN KEY (operator_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_refund_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_record_id BIGINT NOT NULL,
    order_id VARCHAR(128) NULL,
    platform_refund_id VARCHAR(128) NULL,
    refund_type VARCHAR(32) NULL,
    refund_reason VARCHAR(1000) NULL,
    requested_amount DECIMAL(12,2) NULL,
    approved_amount DECIMAL(12,2) NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
    evidence_json LONGTEXT NULL,
    applied_time DATETIME(3) NULL,
    decision_deadline DATETIME(3) NULL,
    platform_status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    dispute_status VARCHAR(40) NULL,
    source VARCHAR(32) NOT NULL,
    sync_status VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL',
    synced_at DATETIME(3) NULL,
    raw_snapshot_hash CHAR(64) NULL,
    last_request_id VARCHAR(80) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_platform (tenant_id, xianyu_account_id, platform_refund_id),
    KEY idx_refund_queue (tenant_id, platform_status, decision_deadline),
    KEY idx_refund_order (tenant_id, order_record_id, updated_time),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_record_id) REFERENCES xianyu_goods_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_refund_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_refund_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    refund_case_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    outcome_state VARCHAR(40) NOT NULL,
    reason VARCHAR(1000) NULL,
    request_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    platform_response_code VARCHAR(100) NULL,
    platform_receipt_json LONGTEXT NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_action_request (tenant_id, request_id),
    KEY idx_refund_action_case (tenant_id, refund_case_id, created_time),
    CONSTRAINT fk_refund_action_case FOREIGN KEY (refund_case_id) REFERENCES xianyu_refund_case (id) ON DELETE CASCADE,
    CONSTRAINT fk_refund_action_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_refund_action_user FOREIGN KEY (operator_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO xianyu_account_dataset_state
    (tenant_id, xianyu_account_id, dataset_code, source, sync_status, coverage_status)
SELECT tenant_id, id, 'ORDERS', 'LOCAL_EVENT',
       CASE WHEN EXISTS (SELECT 1 FROM xianyu_goods_order o WHERE o.xianyu_account_id=xianyu_account.id) THEN 'SUCCEEDED' ELSE 'UNSYNCED' END,
       CASE WHEN EXISTS (SELECT 1 FROM xianyu_goods_order o WHERE o.xianyu_account_id=xianyu_account.id) THEN 'PARTIAL' ELSE 'UNSYNCED' END
FROM xianyu_account
ON DUPLICATE KEY UPDATE dataset_code=VALUES(dataset_code);
