CREATE TABLE xianyu_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    xianyu_account_id BIGINT NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    data_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_owner VARCHAR(100) NULL,
    lease_expire_time DATETIME(3) NULL,
    last_error_message VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_notification_outbox_dedupe (tenant_id, channel_id, event_type, dedupe_key),
    KEY idx_notification_outbox_due (status, next_retry_time, lease_expire_time),
    CONSTRAINT fk_notification_outbox_channel FOREIGN KEY (channel_id)
        REFERENCES xianyu_notification_channel (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
