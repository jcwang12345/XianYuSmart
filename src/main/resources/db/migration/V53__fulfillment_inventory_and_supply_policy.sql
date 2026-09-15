-- V6-FUL-01/04/05：卡密版本、账号级预占归属、库存事件和外部供货熔断/配额。

ALTER TABLE xianyu_kami_config
    ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1 AFTER sharing_mode,
    ADD COLUMN external_daily_quota INT NULL AFTER external_api_timeout_seconds,
    ADD COLUMN external_failure_threshold INT NOT NULL DEFAULT 3 AFTER external_daily_quota,
    ADD COLUMN external_cooldown_seconds INT NOT NULL DEFAULT 300 AFTER external_failure_threshold,
    ADD COLUMN external_circuit_state VARCHAR(16) NOT NULL DEFAULT 'CLOSED' AFTER external_cooldown_seconds,
    ADD COLUMN external_consecutive_failures INT NOT NULL DEFAULT 0 AFTER external_circuit_state,
    ADD COLUMN external_circuit_opened_at DATETIME(3) NULL AFTER external_consecutive_failures,
    ADD COLUMN external_quota_date DATE NULL AFTER external_circuit_opened_at,
    ADD COLUMN external_quota_used INT NOT NULL DEFAULT 0 AFTER external_quota_date;

ALTER TABLE xianyu_kami_item
    ADD COLUMN reserved_account_id BIGINT NULL AFTER order_id,
    ADD COLUMN reservation_token VARCHAR(64) NULL AFTER reserved_account_id,
    ADD COLUMN reservation_expire_time DATETIME(3) NULL AFTER reservation_token,
    ADD COLUMN source_config_version BIGINT NULL AFTER reservation_expire_time,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER source_config_version,
    ADD KEY idx_kami_order_account (tenant_id, reserved_account_id, order_id, status),
    ADD KEY idx_kami_reservation_expiry (tenant_id, status, reservation_expire_time),
    ADD CONSTRAINT fk_kami_reserved_account FOREIGN KEY (reserved_account_id)
        REFERENCES xianyu_account (id) ON DELETE SET NULL;

ALTER TABLE xianyu_kami_usage_record
    ADD COLUMN reservation_token VARCHAR(64) NULL AFTER order_id,
    ADD COLUMN config_version BIGINT NULL AFTER reservation_token,
    ADD COLUMN request_id VARCHAR(80) NULL AFTER config_version,
    ADD KEY idx_kami_usage_request (tenant_id, request_id);

ALTER TABLE xianyu_kami_external_request
    ADD COLUMN payload_fingerprint CHAR(64) NULL AFTER request_token,
    ADD COLUMN result_unknown TINYINT NOT NULL DEFAULT 0 AFTER request_status,
    ADD COLUMN next_retry_time DATETIME(3) NULL AFTER error_message,
    ADD COLUMN circuit_state_at_request VARCHAR(16) NULL AFTER next_retry_time,
    ADD COLUMN quota_used_after INT NULL AFTER circuit_state_at_request,
    ADD COLUMN resolution_decision VARCHAR(32) NULL AFTER quota_used_after,
    ADD COLUMN resolution_note VARCHAR(500) NULL AFTER resolution_decision,
    ADD COLUMN resolution_request_id VARCHAR(80) NULL AFTER resolution_note,
    ADD COLUMN resolved_by BIGINT NULL AFTER resolution_request_id,
    ADD COLUMN resolved_time DATETIME(3) NULL AFTER resolved_by,
    ADD UNIQUE KEY uk_kami_external_resolution_request (tenant_id, resolution_request_id),
    ADD KEY idx_kami_external_review (tenant_id, kami_config_id, result_unknown, update_time),
    ADD CONSTRAINT fk_kami_external_resolved_user FOREIGN KEY (resolved_by)
        REFERENCES sys_user (id) ON DELETE SET NULL;

CREATE TABLE xianyu_kami_inventory_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    kami_config_id BIGINT NOT NULL,
    kami_item_id BIGINT NULL,
    xianyu_account_id BIGINT NULL,
    order_id VARCHAR(100) NULL,
    event_type VARCHAR(32) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    outcome_state VARCHAR(32) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    reservation_token VARCHAR(64) NULL,
    config_version BIGINT NULL,
    quantity INT NOT NULL DEFAULT 1,
    before_json TEXT NULL,
    after_json TEXT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kami_inventory_event_key (tenant_id, event_key),
    KEY idx_kami_inventory_event_config (tenant_id, kami_config_id, created_time),
    KEY idx_kami_inventory_event_order (tenant_id, xianyu_account_id, order_id, created_time),
    CONSTRAINT fk_kami_inventory_event_config FOREIGN KEY (kami_config_id)
        REFERENCES xianyu_kami_config (id) ON DELETE CASCADE,
    CONSTRAINT fk_kami_inventory_event_item FOREIGN KEY (kami_item_id)
        REFERENCES xianyu_kami_item (id) ON DELETE SET NULL,
    CONSTRAINT fk_kami_inventory_event_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE SET NULL,
    CONSTRAINT fk_kami_inventory_event_user FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_fixed_delivery_template
    ADD COLUMN template_version BIGINT NOT NULL DEFAULT 1 AFTER xianyu_account_id;

CREATE TABLE xianyu_fixed_delivery_template_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version BIGINT NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    delivery_content TEXT NOT NULL,
    message_template VARCHAR(1000) NOT NULL,
    account_ids_json TEXT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fixed_template_version (tenant_id, template_id, template_version),
    KEY idx_fixed_template_version_request (tenant_id, request_id),
    CONSTRAINT fk_fixed_template_version_template FOREIGN KEY (template_id)
        REFERENCES xianyu_fixed_delivery_template (id) ON DELETE CASCADE,
    CONSTRAINT fk_fixed_template_version_user FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO xianyu_fixed_delivery_template_version
(tenant_id,template_id,template_version,template_name,delivery_content,message_template,
 account_ids_json,request_id,operator_username)
SELECT template.tenant_id,template.id,1,template.template_name,template.delivery_content,
       template.message_template,
       CONCAT('[',GROUP_CONCAT(link.xianyu_account_id ORDER BY link.xianyu_account_id SEPARATOR ','),']'),
       CONCAT('migration-v53-',template.id),'migration'
  FROM xianyu_fixed_delivery_template template
  JOIN xianyu_fixed_delivery_template_account link ON link.template_id=template.id
 GROUP BY template.tenant_id,template.id,template.template_name,template.delivery_content,template.message_template;

CREATE TABLE xianyu_order_engagement_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_record_id BIGINT NULL,
    order_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_key VARCHAR(180) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    content_sha256 CHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    next_retry_time DATETIME(3) NULL,
    sent_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_engagement_event (tenant_id, event_key),
    KEY idx_order_engagement_daily (tenant_id, xianyu_account_id, event_type, created_time),
    KEY idx_order_engagement_status (tenant_id, status, next_retry_time),
    CONSTRAINT fk_order_engagement_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_engagement_order FOREIGN KEY (order_record_id)
        REFERENCES xianyu_goods_order (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
