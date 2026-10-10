-- PDCA-B03：真实店铺画像同步证据、尝试状态与恢复信息。

ALTER TABLE xianyu_shop_profile_snapshot
    ADD COLUMN field_evidence_json JSON NULL AFTER raw_snapshot_json,
    ADD COLUMN collector_version VARCHAR(40) NULL AFTER field_evidence_json,
    ADD COLUMN platform_online_status VARCHAR(32) NULL AFTER collector_version,
    ADD COLUMN certification_labels_json JSON NULL AFTER platform_online_status,
    ADD COLUMN platform_identity_json JSON NULL AFTER certification_labels_json,
    ADD COLUMN shop_authorization_json JSON NULL AFTER platform_identity_json;

ALTER TABLE xianyu_account_dataset_state
    ADD COLUMN started_at DATETIME(3) NULL AFTER last_attempt_time,
    ADD COLUMN retry_after DATETIME(3) NULL AFTER last_error_message,
    ADD COLUMN recovery_hint VARCHAR(500) NULL AFTER retry_after;

CREATE TABLE xianyu_shop_profile_sync_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    trigger_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(24) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'NONE',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    retry_after DATETIME(3) NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    recovery_hint VARCHAR(500) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_sync_request (tenant_id, xianyu_account_id, request_id),
    KEY idx_profile_sync_status (tenant_id, xianyu_account_id, status, started_at),
    CONSTRAINT fk_profile_sync_attempt_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
