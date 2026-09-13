-- ACC-03、DASH-01~03、IM-03、NTF-02 第二批经营成熟度基础。

CREATE TABLE xianyu_account_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    color VARCHAR(16) NULL,
    description VARCHAR(500) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_group_name (tenant_id, group_name),
    KEY idx_account_group_sort (tenant_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_account_group_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_group_member (tenant_id, group_id, xianyu_account_id),
    KEY idx_account_member_groups (tenant_id, xianyu_account_id, group_id),
    CONSTRAINT fk_account_group_member_group FOREIGN KEY (group_id) REFERENCES xianyu_account_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_group_member_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_shop_metric_daily (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    metric_date DATE NOT NULL,
    gmv DECIMAL(16,2) NULL,
    paid_order_count BIGINT NULL,
    paid_buyer_count BIGINT NULL,
    refund_amount DECIMAL(16,2) NULL,
    refund_order_count BIGINT NULL,
    exposure_count BIGINT NULL,
    visitor_count BIGINT NULL,
    inquiry_count BIGINT NULL,
    replied_inquiry_count BIGINT NULL,
    active_product_count BIGINT NULL,
    source VARCHAR(32) NOT NULL,
    sync_status VARCHAR(24) NOT NULL DEFAULT 'SUCCEEDED',
    coverage_status VARCHAR(24) NOT NULL DEFAULT 'PARTIAL',
    sample_size BIGINT NOT NULL DEFAULT 0,
    synced_at DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_metric_day (tenant_id, xianyu_account_id, metric_date),
    KEY idx_shop_metric_range (tenant_id, metric_date, xianyu_account_id),
    CONSTRAINT fk_shop_metric_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conversation_assignment
    ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0 AFTER priority,
    ADD COLUMN keyword_flag VARCHAR(100) NULL AFTER pinned,
    ADD COLUMN customer_note VARCHAR(500) NULL AFTER keyword_flag,
    ADD COLUMN customer_blacklisted TINYINT NOT NULL DEFAULT 0 AFTER customer_note,
    ADD KEY idx_conversation_pinned (tenant_id, pinned, last_message_time);

ALTER TABLE xianyu_notification_channel
    ADD COLUMN scope_type VARCHAR(16) NOT NULL DEFAULT 'ALL' AFTER event_types,
    ADD COLUMN scope_ids_json TEXT NULL AFTER scope_type;
