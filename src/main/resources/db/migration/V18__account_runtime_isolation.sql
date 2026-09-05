-- 账号级桌面 Web 运行档案。一个闲鱼账号只绑定一个稳定档案，不按租户共享。
CREATE TABLE xianyu_device_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    profile_key VARCHAR(64) NOT NULL,
    profile_type VARCHAR(32) NOT NULL DEFAULT 'DESKTOP_WEB',
    platform VARCHAR(32) NOT NULL,
    locale VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    viewport_width INT NOT NULL,
    viewport_height INT NOT NULL,
    device_scale_factor DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    color_scheme VARCHAR(16) NOT NULL DEFAULT 'light',
    browser_version VARCHAR(64) NULL,
    browser_storage_state LONGTEXT NULL,
    storage_state_updated_time DATETIME(3) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_profile_account (xianyu_account_id),
    UNIQUE KEY uk_device_profile_tenant_key (tenant_id, profile_key),
    KEY idx_device_profile_tenant_status (tenant_id, status),
    CONSTRAINT fk_device_profile_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 防止旧凭证实体覆盖较新的登录/刷新结果。
ALTER TABLE xianyu_cookie
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 AFTER token_expire_time;

-- 后台任务没有 HTTP 租户上下文时，仍从账号关系继承租户。
CREATE TRIGGER trg_device_profile_tenant BEFORE INSERT ON xianyu_device_profile
FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
