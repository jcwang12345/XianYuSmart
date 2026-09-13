-- ACC-01/02/04/05/07 + AUD-01 基础：接入通道、店铺画像快照、处罚事件和可追踪审计。

CREATE TABLE xianyu_account_access_channel (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    channel_code VARCHAR(40) NOT NULL,
    channel_name VARCHAR(100) NOT NULL,
    connection_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    authorization_status VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
    authorization_scope TEXT NULL,
    credential_expire_time DATETIME(3) NULL,
    capabilities_json JSON NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    last_checked_time DATETIME(3) NULL,
    last_success_time DATETIME(3) NULL,
    last_error_code VARCHAR(80) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_access_channel (tenant_id, xianyu_account_id, channel_code),
    KEY idx_access_channel_health (tenant_id, connection_status, authorization_status),
    KEY idx_access_channel_expire (tenant_id, credential_expire_time),
    CONSTRAINT fk_access_channel_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_account_dataset_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    dataset_code VARCHAR(40) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    sync_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    as_of_time DATETIME(3) NULL,
    last_attempt_time DATETIME(3) NULL,
    last_success_time DATETIME(3) NULL,
    last_error_code VARCHAR(80) NULL,
    last_error_message VARCHAR(1000) NULL,
    request_id VARCHAR(80) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_dataset (tenant_id, xianyu_account_id, dataset_code),
    KEY idx_dataset_sync_state (tenant_id, dataset_code, sync_status, coverage_status),
    CONSTRAINT fk_dataset_state_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO xianyu_account_access_channel
    (tenant_id, xianyu_account_id, channel_code, channel_name, connection_status,
     authorization_status, credential_expire_time, capabilities_json, source,
     coverage_status, last_checked_time, last_success_time, last_error_message)
SELECT account.tenant_id, account.id, 'QR_COOKIE', '扫码/Cookie 接入',
       CASE
           WHEN account.status <> 1 THEN 'DEGRADED'
           WHEN cookie.cookie_status = 1 THEN 'CONNECTED'
           WHEN cookie.cookie_status IN (2, 3) THEN 'EXPIRED'
           ELSE 'UNKNOWN'
       END,
       'NOT_APPLICABLE', cookie.expire_time,
       JSON_OBJECT('messaging', 'UNKNOWN', 'orders', 'UNKNOWN', 'products', 'UNKNOWN',
                   'publishing', 'UNKNOWN', 'refunds', 'NOT_IMPLEMENTED', 'marketing', 'UNKNOWN'),
       'MIGRATION',
       CASE WHEN cookie.id IS NULL THEN 'UNSYNCED' ELSE 'PARTIAL' END,
       NOW(3), CASE WHEN cookie.cookie_status = 1 THEN NOW(3) ELSE NULL END,
       CASE WHEN cookie.id IS NULL THEN '未检测到凭证记录' ELSE NULL END
FROM xianyu_account account
LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id = account.id;

CREATE TABLE xianyu_shop_profile_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    sync_status VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL',
    request_id VARCHAR(80) NULL,
    shop_nickname VARCHAR(200) NULL,
    avatar_url VARCHAR(2000) NULL,
    shop_home_url VARCHAR(2000) NULL,
    region VARCHAR(200) NULL,
    shop_level VARCHAR(100) NULL,
    shop_score DECIMAL(12, 2) NULL,
    super_seller TINYINT NULL,
    seller_credit VARCHAR(100) NULL,
    buyer_credit VARCHAR(100) NULL,
    sesame_verified TINYINT NULL,
    real_name_verified TINYINT NULL,
    xianyu_expert TINYINT NULL,
    user_type VARCHAR(100) NULL,
    xianyu_upgraded TINYINT NULL,
    taobao_bound TINYINT NULL,
    pin_limit INT NULL,
    on_sale_count INT NULL,
    sold_count INT NULL,
    follower_count BIGINT NULL,
    positive_rate DECIMAL(7, 4) NULL,
    total_review_count BIGINT NULL,
    total_bought_count BIGINT NULL,
    total_sold_count BIGINT NULL,
    last_active_time DATETIME(3) NULL,
    synced_at DATETIME(3) NULL,
    raw_snapshot_hash CHAR(64) NULL,
    raw_snapshot_json LONGTEXT NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_profile_latest (tenant_id, xianyu_account_id, created_time),
    KEY idx_profile_coverage (tenant_id, coverage_status, sync_status),
    UNIQUE KEY uk_profile_request (tenant_id, request_id),
    CONSTRAINT fk_profile_snapshot_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_shop_risk_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL,
    platform_penalty_id VARCHAR(128) NULL,
    rule_code VARCHAR(128) NULL,
    risk_name VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    impact_summary VARCHAR(1000) NULL,
    risk_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    effective_time DATETIME(3) NULL,
    penalty_time DATETIME(3) NULL,
    appeal_deadline DATETIME(3) NULL,
    appeal_status VARCHAR(24) NOT NULL DEFAULT 'NOT_AVAILABLE',
    recommended_action VARCHAR(1000) NULL,
    operation_advice VARCHAR(2000) NULL,
    source VARCHAR(32) NOT NULL,
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL',
    synced_at DATETIME(3) NULL,
    raw_snapshot_hash CHAR(64) NULL,
    request_id VARCHAR(80) NULL,
    local_handling_status VARCHAR(24) NOT NULL DEFAULT 'UNHANDLED',
    local_handling_note VARCHAR(1000) NULL,
    handled_by BIGINT NULL,
    handled_at DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_risk_dedupe (tenant_id, xianyu_account_id, dedupe_key),
    KEY idx_shop_risk_queue (tenant_id, risk_status, severity, appeal_deadline),
    KEY idx_shop_risk_account_sync (tenant_id, xianyu_account_id, synced_at),
    CONSTRAINT fk_shop_risk_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_risk_handler FOREIGN KEY (handled_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_shop_risk_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    risk_event_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NOT NULL,
    note VARCHAR(1000) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    request_id VARCHAR(80) NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_risk_action_request (tenant_id, request_id),
    KEY idx_shop_risk_action_event (tenant_id, risk_event_id, created_time),
    CONSTRAINT fk_shop_risk_action_event FOREIGN KEY (risk_event_id) REFERENCES xianyu_shop_risk_event (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_risk_action_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_risk_action_user FOREIGN KEY (operator_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_operation_log
    ADD COLUMN request_id VARCHAR(80) NULL AFTER operator_username,
    ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER request_id,
    ADD COLUMN outcome_state VARCHAR(32) NULL AFTER operation_status,
    ADD COLUMN data_source VARCHAR(32) NULL AFTER outcome_state,
    ADD COLUMN platform_response_code VARCHAR(100) NULL AFTER response_result,
    ADD COLUMN field_diff_json LONGTEXT NULL AFTER platform_response_code,
    ADD KEY idx_operation_request (tenant_id, request_id),
    ADD KEY idx_operation_outcome_time (tenant_id, outcome_state, create_time);

INSERT IGNORE INTO sys_user_permission (user_id, permission_code)
SELECT users.id, permissions.permission_code
FROM sys_user users
CROSS JOIN (
    SELECT 'action:account-delete' permission_code UNION ALL
    SELECT 'action:credential-write' UNION ALL
    SELECT 'action:account-batch' UNION ALL
    SELECT 'action:risk-export' UNION ALL
    SELECT 'action:risk-handle' UNION ALL
    SELECT 'action:goods-delete' UNION ALL
    SELECT 'action:goods-batch-price' UNION ALL
    SELECT 'action:refund-approve' UNION ALL
    SELECT 'action:refund-reject' UNION ALL
    SELECT 'action:kami-export' UNION ALL
    SELECT 'action:audit-export' UNION ALL
    SELECT 'action:member-permission-write'
) permissions
WHERE users.role = 'USER';
