-- V6-PUB-02/03/04/05/07：版本化本地参考目录、不可变草稿历史与持久化预检快照。
-- 本地目录不是闲鱼平台真值；真实通道仍必须经过平台预检并展示差异。

CREATE TABLE xianyu_listing_catalog_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_code VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_REFERENCE',
    verification_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_PLATFORM_PREFLIGHT',
    active TINYINT NOT NULL DEFAULT 1,
    effective_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_catalog_version (version_code),
    KEY idx_listing_catalog_active (active, effective_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_listing_catalog_industry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    catalog_version_id BIGINT NOT NULL,
    listing_type VARCHAR(24) NOT NULL,
    industry_code VARCHAR(64) NOT NULL,
    industry_name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_industry (catalog_version_id, listing_type, industry_code),
    CONSTRAINT fk_listing_industry_version FOREIGN KEY (catalog_version_id)
        REFERENCES xianyu_listing_catalog_version (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_listing_catalog_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    catalog_version_id BIGINT NOT NULL,
    listing_type VARCHAR(24) NOT NULL,
    industry_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_category (catalog_version_id, listing_type, category_code),
    KEY idx_listing_category_industry (catalog_version_id, listing_type, industry_code, sort_order),
    CONSTRAINT fk_listing_category_version FOREIGN KEY (catalog_version_id)
        REFERENCES xianyu_listing_catalog_version (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_listing_catalog_attribute (
    id BIGINT NOT NULL AUTO_INCREMENT,
    catalog_version_id BIGINT NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    attribute_code VARCHAR(64) NOT NULL,
    attribute_name VARCHAR(100) NOT NULL,
    control_type VARCHAR(24) NOT NULL DEFAULT 'TEXT',
    required_flag TINYINT NOT NULL DEFAULT 0,
    options_json LONGTEXT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_attribute (catalog_version_id, category_code, attribute_code),
    KEY idx_listing_attribute_category (catalog_version_id, category_code, sort_order),
    CONSTRAINT fk_listing_attribute_version FOREIGN KEY (catalog_version_id)
        REFERENCES xianyu_listing_catalog_version (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO xianyu_listing_catalog_version
    (version_code, source, verification_status, active, effective_time)
VALUES ('LOCAL-2026.09.15-1', 'LOCAL_REFERENCE', 'PENDING_PLATFORM_PREFLIGHT', 1, CURRENT_TIMESTAMP(3));

SET @listing_catalog_version_id = LAST_INSERT_ID();

INSERT INTO xianyu_listing_catalog_industry
    (catalog_version_id, listing_type, industry_code, industry_name, sort_order)
VALUES
    (@listing_catalog_version_id, 'PHYSICAL', 'DIGITAL_DEVICE', '数码与电脑', 10),
    (@listing_catalog_version_id, 'PHYSICAL', 'OFFICE_SUPPLY', '办公与文具', 20),
    (@listing_catalog_version_id, 'VIRTUAL', 'SOFTWARE', '软件与数字工具', 10),
    (@listing_catalog_version_id, 'VIRTUAL', 'DIGITAL_CONTENT', '数字内容与卡券', 20),
    (@listing_catalog_version_id, 'SERVICE', 'REMOTE_SERVICE', '远程专业服务', 10),
    (@listing_catalog_version_id, 'SERVICE', 'LOCAL_SERVICE', '本地到店与上门服务', 20);

INSERT INTO xianyu_listing_catalog_category
    (catalog_version_id, listing_type, industry_code, category_code, category_name, sort_order)
VALUES
    (@listing_catalog_version_id, 'PHYSICAL', 'DIGITAL_DEVICE', 'COMPUTER_ACCESSORY', '电脑配件', 10),
    (@listing_catalog_version_id, 'PHYSICAL', 'DIGITAL_DEVICE', 'OFFICE_DEVICE', '办公设备', 20),
    (@listing_catalog_version_id, 'PHYSICAL', 'OFFICE_SUPPLY', 'WRITING_SUPPLY', '书写工具', 10),
    (@listing_catalog_version_id, 'PHYSICAL', 'OFFICE_SUPPLY', 'PAPER_SUPPLY', '纸品耗材', 20),
    (@listing_catalog_version_id, 'VIRTUAL', 'SOFTWARE', 'OFFICE_PLUGIN', '办公软件与插件', 10),
    (@listing_catalog_version_id, 'VIRTUAL', 'SOFTWARE', 'UTILITY_SOFTWARE', '工具软件', 20),
    (@listing_catalog_version_id, 'VIRTUAL', 'DIGITAL_CONTENT', 'ONLINE_TUTORIAL', '在线教程与指导', 10),
    (@listing_catalog_version_id, 'VIRTUAL', 'DIGITAL_CONTENT', 'VIRTUAL_CARD', '虚拟卡券', 20),
    (@listing_catalog_version_id, 'SERVICE', 'REMOTE_SERVICE', 'REMOTE_INSTALLATION', '远程安装与指导', 10),
    (@listing_catalog_version_id, 'SERVICE', 'REMOTE_SERVICE', 'ONLINE_CONSULTING', '在线咨询服务', 20),
    (@listing_catalog_version_id, 'SERVICE', 'LOCAL_SERVICE', 'ON_SITE_SERVICE', '上门服务', 10),
    (@listing_catalog_version_id, 'SERVICE', 'LOCAL_SERVICE', 'STORE_APPOINTMENT', '到店预约服务', 20);

INSERT INTO xianyu_listing_catalog_attribute
    (catalog_version_id, category_code, attribute_code, attribute_name, control_type, required_flag, options_json, sort_order)
VALUES
    (@listing_catalog_version_id, 'COMPUTER_ACCESSORY', 'brand', '品牌', 'TEXT', 0, NULL, 10),
    (@listing_catalog_version_id, 'COMPUTER_ACCESSORY', 'model', '型号', 'TEXT', 1, NULL, 20),
    (@listing_catalog_version_id, 'OFFICE_DEVICE', 'brand', '品牌', 'TEXT', 0, NULL, 10),
    (@listing_catalog_version_id, 'OFFICE_DEVICE', 'warranty', '保修情况', 'SELECT', 0, '["无保修","店保","官方保修"]', 20),
    (@listing_catalog_version_id, 'WRITING_SUPPLY', 'brand', '品牌', 'TEXT', 0, NULL, 10),
    (@listing_catalog_version_id, 'WRITING_SUPPLY', 'specification', '规格型号', 'TEXT', 1, NULL, 20),
    (@listing_catalog_version_id, 'PAPER_SUPPLY', 'size', '尺寸', 'SELECT', 1, '["A4","A5","其他"]', 10),
    (@listing_catalog_version_id, 'OFFICE_PLUGIN', 'softwarePlatform', '适用平台', 'SELECT', 1, '["Office","WPS","Office + WPS"]', 10),
    (@listing_catalog_version_id, 'OFFICE_PLUGIN', 'licenseDays', '授权有效期', 'SELECT', 1, '["7天","30天","永久"]', 20),
    (@listing_catalog_version_id, 'UTILITY_SOFTWARE', 'operatingSystem', '操作系统', 'SELECT', 1, '["Windows","macOS","多平台"]', 10),
    (@listing_catalog_version_id, 'ONLINE_TUTORIAL', 'deliveryFormat', '交付形式', 'SELECT', 1, '["视频","文档","社群指导"]', 10),
    (@listing_catalog_version_id, 'VIRTUAL_CARD', 'validity', '有效期', 'SELECT', 1, '["即时生效","7天","30天"]', 10),
    (@listing_catalog_version_id, 'REMOTE_INSTALLATION', 'supportedPlatform', '支持平台', 'SELECT', 1, '["Windows","macOS","多平台"]', 10),
    (@listing_catalog_version_id, 'REMOTE_INSTALLATION', 'serviceWindow', '服务时段', 'TEXT', 1, NULL, 20),
    (@listing_catalog_version_id, 'ONLINE_CONSULTING', 'consultingTopic', '咨询方向', 'TEXT', 1, NULL, 10),
    (@listing_catalog_version_id, 'ON_SITE_SERVICE', 'serviceArea', '服务范围', 'TEXT', 1, NULL, 10),
    (@listing_catalog_version_id, 'STORE_APPOINTMENT', 'storeName', '到店门店', 'TEXT', 1, NULL, 10);

ALTER TABLE xianyu_listing_draft
    ADD COLUMN catalog_version VARCHAR(64) NULL AFTER revision,
    ADD COLUMN payload_fingerprint CHAR(64) NULL AFTER catalog_version,
    ADD KEY idx_listing_draft_fingerprint (tenant_id, payload_fingerprint);

CREATE TABLE xianyu_listing_draft_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    draft_id BIGINT NOT NULL,
    revision INT NOT NULL,
    change_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL_SAVE',
    catalog_version VARCHAR(64) NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_draft_revision (tenant_id, draft_id, revision),
    KEY idx_listing_draft_version_created (tenant_id, draft_id, created_time),
    CONSTRAINT fk_listing_draft_version_draft FOREIGN KEY (draft_id)
        REFERENCES xianyu_listing_draft (id) ON DELETE CASCADE,
    CONSTRAINT fk_listing_draft_version_operator FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

UPDATE xianyu_listing_draft
   SET catalog_version='LOCAL-2026.09.15-1',
       payload_fingerprint=SHA2(payload_json, 256)
 WHERE catalog_version IS NULL OR payload_fingerprint IS NULL;

INSERT IGNORE INTO xianyu_listing_draft_version
    (tenant_id,draft_id,revision,change_source,catalog_version,payload_fingerprint,payload_json,
     operator_user_id,operator_username,created_time)
SELECT tenant_id,id,revision,'MIGRATION_BACKFILL',catalog_version,payload_fingerprint,payload_json,
       operator_user_id,operator_username,updated_time
  FROM xianyu_listing_draft;

CREATE TABLE xianyu_listing_preflight_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    preview_token VARCHAR(64) NOT NULL,
    draft_id BIGINT NULL,
    draft_revision INT NULL,
    catalog_version VARCHAR(64) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    capability_fingerprint CHAR(64) NOT NULL,
    validation_json LONGTEXT NOT NULL,
    platform_result_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    expires_time DATETIME(3) NOT NULL,
    consumed_time DATETIME(3) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_preflight_token (tenant_id, preview_token),
    UNIQUE KEY uk_listing_preflight_request (tenant_id, request_id),
    KEY idx_listing_preflight_account (tenant_id, xianyu_account_id, status, expires_time),
    CONSTRAINT fk_listing_preflight_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_listing_preflight_draft FOREIGN KEY (draft_id)
        REFERENCES xianyu_listing_draft (id) ON DELETE SET NULL,
    CONSTRAINT fk_listing_preflight_operator FOREIGN KEY (operator_user_id)
        REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
