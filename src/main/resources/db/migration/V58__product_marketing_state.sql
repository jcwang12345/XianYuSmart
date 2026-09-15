-- PRD-07 / PUB-10: keep local marketing intent separate from platform-observed facts.
-- Unknown platform values remain NULL and must never be rendered as zero or disabled.

CREATE TABLE xianyu_goods_marketing_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,

    desired_fan_all_price DECIMAL(12,2) NULL,
    desired_fan_old_price DECIMAL(12,2) NULL,
    desired_fan_buyer_price DECIMAL(12,2) NULL,
    desired_bargain_enabled TINYINT NULL,
    desired_bargain_price DECIMAL(12,2) NULL,
    desired_bargain_quantity INT NULL,
    desired_coin_enabled TINYINT NULL,
    desired_coin_discount_percent INT NULL,

    platform_fan_all_price DECIMAL(12,2) NULL,
    platform_fan_old_price DECIMAL(12,2) NULL,
    platform_fan_buyer_price DECIMAL(12,2) NULL,
    platform_bargain_enabled TINYINT NULL,
    platform_bargain_price DECIMAL(12,2) NULL,
    platform_bargain_quantity INT NULL,
    platform_coin_enabled TINYINT NULL,
    platform_coin_discount_percent INT NULL,
    coin_agreement_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    coin_balance DECIMAL(14,2) NULL,

    coverage_status VARCHAR(24) NOT NULL DEFAULT 'UNSYNCED',
    data_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_DRAFT',
    platform_synced_at DATETIME(3) NULL,
    last_request_id VARCHAR(80) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_marketing (tenant_id, xianyu_account_id, xy_goods_id),
    KEY idx_goods_marketing_evidence (tenant_id, coverage_status, platform_synced_at),
    CONSTRAINT fk_goods_marketing_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
