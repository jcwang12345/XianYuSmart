-- 运营素材、自动回复、固定发货和卡密仓库支持一对多账号关联。
-- 保留各主表的 xianyu_account_id 作为创建者/兼容主账号，运行时以关联表为准。

CREATE TABLE merchant_resource_account (
    resource_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (resource_id, xianyu_account_id),
    KEY idx_resource_account_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_resource_account_resource FOREIGN KEY (resource_id) REFERENCES merchant_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_account_xianyu FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO merchant_resource_account (resource_id, tenant_id, xianyu_account_id)
SELECT id, tenant_id, xianyu_account_id FROM merchant_resource WHERE xianyu_account_id IS NOT NULL;

CREATE TABLE xianyu_keyword_reply_rule_account (
    rule_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rule_id, xianyu_account_id),
    KEY idx_keyword_rule_account_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_keyword_rule_account_rule FOREIGN KEY (rule_id) REFERENCES xianyu_keyword_reply_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_keyword_rule_account_xianyu FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO xianyu_keyword_reply_rule_account (rule_id, tenant_id, xianyu_account_id)
SELECT id, tenant_id, xianyu_account_id FROM xianyu_keyword_reply_rule;

ALTER TABLE xianyu_keyword_reply_rule
    ADD COLUMN sharing_scope VARCHAR(16) NOT NULL DEFAULT 'GOODS' AFTER xy_goods_id;

CREATE TABLE xianyu_fixed_delivery_template_account (
    template_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (template_id, xianyu_account_id),
    KEY idx_fixed_template_account_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_fixed_template_account_template FOREIGN KEY (template_id) REFERENCES xianyu_fixed_delivery_template (id) ON DELETE CASCADE,
    CONSTRAINT fk_fixed_template_account_xianyu FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO xianyu_fixed_delivery_template_account (template_id, tenant_id, xianyu_account_id)
SELECT id, tenant_id, xianyu_account_id FROM xianyu_fixed_delivery_template;

ALTER TABLE xianyu_kami_config
    ADD COLUMN sharing_mode VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' AFTER xianyu_account_id;

CREATE TABLE xianyu_kami_config_account (
    kami_config_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (kami_config_id, xianyu_account_id),
    KEY idx_kami_config_account_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_kami_config_account_config FOREIGN KEY (kami_config_id) REFERENCES xianyu_kami_config (id) ON DELETE CASCADE,
    CONSTRAINT fk_kami_config_account_xianyu FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO xianyu_kami_config_account (kami_config_id, tenant_id, xianyu_account_id)
SELECT id, tenant_id, xianyu_account_id FROM xianyu_kami_config;
