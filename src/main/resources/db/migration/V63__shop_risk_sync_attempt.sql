-- B03-R2：处罚只读同步幂等、互斥、终态与审计关联。

CREATE TABLE xianyu_shop_risk_sync_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    trigger_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(24) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'NONE',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    record_count INT NULL,
    page_count INT NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    recovery_hint VARCHAR(500) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_sync_request (tenant_id, xianyu_account_id, request_id),
    KEY idx_risk_sync_status (tenant_id, xianyu_account_id, status, started_at),
    CONSTRAINT fk_risk_sync_attempt_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
