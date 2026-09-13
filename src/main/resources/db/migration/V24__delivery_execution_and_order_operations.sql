ALTER TABLE xianyu_goods_order
    ADD COLUMN external_attempt_started TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN platform_trade_status VARCHAR(40) NULL,
    ADD COLUMN platform_status_checked_at DATETIME(3) NULL;

CREATE TABLE xianyu_order_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_id VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_owner VARCHAR(64) NULL,
    lease_expire_time DATETIME(3) NULL,
    last_error VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_confirmation (tenant_id, xianyu_account_id, order_id),
    KEY idx_confirmation_due (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing orders are not automatically confirmed by a migration.
ALTER TABLE xianyu_goods_sku ADD COLUMN display_name VARCHAR(100) NULL;
ALTER TABLE xianyu_goods_auto_reply_record ADD COLUMN external_attempt_started TINYINT NOT NULL DEFAULT 0;

CREATE TABLE xianyu_reply_preference (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(128) NOT NULL,
    welcome_enabled TINYINT NOT NULL DEFAULT 0,
    welcome_text TEXT NULL,
    welcome_image_url VARCHAR(2000) NULL,
    bargain_floor DECIMAL(12,2) NULL,
    UNIQUE KEY uk_reply_preference (tenant_id, xianyu_account_id, xy_goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE xianyu_welcome_claim (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(128) NOT NULL,
    buyer_user_id VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_welcome (tenant_id, xianyu_account_id, xy_goods_id, buyer_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
