-- ORD-04/05：售后类型、关键期限与退货/换货物流证据。
-- 本迁移只保存平台同步或人工已确认的事实，不代表系统具备退款/退货平台写能力。

ALTER TABLE xianyu_refund_case
    ADD COLUMN after_sales_type VARCHAR(32) NULL AFTER refund_type,
    ADD COLUMN return_status VARCHAR(40) NULL AFTER dispute_status,
    ADD COLUMN seller_decision_deadline DATETIME(3) NULL AFTER decision_deadline,
    ADD COLUMN buyer_return_deadline DATETIME(3) NULL AFTER seller_decision_deadline,
    ADD COLUMN platform_action_required VARCHAR(64) NULL AFTER buyer_return_deadline,
    ADD COLUMN latest_status_message VARCHAR(1000) NULL AFTER platform_action_required,
    ADD KEY idx_refund_return_queue (tenant_id, return_status, buyer_return_deadline);

CREATE TABLE xianyu_return_shipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    refund_case_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_record_id BIGINT NOT NULL,
    direction VARCHAR(32) NOT NULL,
    logistics_company_code VARCHAR(64) NULL,
    logistics_company_name VARCHAR(128) NOT NULL,
    tracking_number VARCHAR(128) NOT NULL,
    shipment_status VARCHAR(40) NOT NULL,
    latest_event VARCHAR(1000) NULL,
    shipped_time DATETIME(3) NULL,
    received_time DATETIME(3) NULL,
    source VARCHAR(32) NOT NULL,
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL',
    synced_at DATETIME(3) NULL,
    raw_snapshot_hash CHAR(64) NULL,
    request_id VARCHAR(80) NOT NULL,
    created_by BIGINT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_shipment_request (tenant_id, request_id),
    UNIQUE KEY uk_return_shipment_tracking (tenant_id, refund_case_id, direction, tracking_number),
    KEY idx_return_shipment_case (tenant_id, refund_case_id, updated_time),
    KEY idx_return_shipment_tracking (tenant_id, tracking_number),
    CONSTRAINT fk_return_shipment_refund FOREIGN KEY (refund_case_id) REFERENCES xianyu_refund_case (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_shipment_order FOREIGN KEY (order_record_id) REFERENCES xianyu_goods_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_shipment_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_shipment_user FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
