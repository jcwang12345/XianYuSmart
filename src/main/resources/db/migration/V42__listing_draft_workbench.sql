-- PUB-01/02：完整商品发布工作台草稿。高级字段先持久化并进入预检，
-- 只有通道能力证据允许时才进入真实平台请求，避免静默丢字段。

CREATE TABLE xianyu_listing_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    draft_name VARCHAR(200) NOT NULL,
    listing_type VARCHAR(24) NOT NULL,
    publish_channel VARCHAR(40) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    revision INT NOT NULL DEFAULT 1,
    payload_json LONGTEXT NOT NULL,
    data_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_DRAFT',
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_listing_draft_scope (tenant_id, xianyu_account_id, status, updated_time),
    KEY idx_listing_draft_operator (tenant_id, operator_user_id, updated_time),
    CONSTRAINT fk_listing_draft_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_listing_draft_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
