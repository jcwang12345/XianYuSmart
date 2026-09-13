-- 2.1.0 多账号运营驾驶舱、团队按店授权、状态化异常任务与平台能力矩阵

ALTER TABLE sys_user
    ADD COLUMN tenant_id BIGINT NULL AFTER id,
    ADD COLUMN member_role VARCHAR(24) NOT NULL DEFAULT 'OWNER' AFTER role,
    ADD COLUMN account_scope_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' AFTER member_role,
    ADD COLUMN totp_enabled TINYINT NOT NULL DEFAULT 0 AFTER account_scope_mode,
    ADD COLUMN totp_secret VARCHAR(500) NULL AFTER totp_enabled,
    ADD COLUMN totp_recovery_codes TEXT NULL AFTER totp_secret;

-- 兼容此前“用户 ID 即租户 ID”的数据模型，不改变升级前的数据归属。
UPDATE sys_user SET tenant_id = id WHERE tenant_id IS NULL;
ALTER TABLE sys_user MODIFY COLUMN tenant_id BIGINT NOT NULL;
CREATE INDEX idx_sys_user_tenant_status ON sys_user (tenant_id, status);

CREATE TABLE sys_user_account_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_scope (user_id, xianyu_account_id),
    KEY idx_user_account_scope_account (xianyu_account_id, user_id),
    CONSTRAINT fk_user_account_scope_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_account_scope_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE operational_issue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    issue_type VARCHAR(48) NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    xianyu_account_id BIGINT NULL,
    source_type VARCHAR(48) NULL,
    source_id VARCHAR(128) NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NULL,
    resolution_note VARCHAR(1000) NULL,
    assigned_user_id BIGINT NULL,
    assigned_username VARCHAR(50) NULL,
    due_time DATETIME(3) NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    first_occurred_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_occurred_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_operational_issue_dedupe (tenant_id, dedupe_key),
    KEY idx_operational_issue_queue (tenant_id, status, severity, last_occurred_time),
    KEY idx_operational_issue_account (tenant_id, xianyu_account_id, status),
    KEY idx_operational_issue_assignee (tenant_id, assigned_user_id, status),
    CONSTRAINT fk_operational_issue_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE SET NULL,
    CONSTRAINT fk_operational_issue_assignee FOREIGN KEY (assigned_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_account_capability (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    capability_code VARCHAR(64) NOT NULL,
    capability_name VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_PROBE',
    detail VARCHAR(500) NULL,
    checked_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_capability (tenant_id, xianyu_account_id, capability_code),
    KEY idx_account_capability_status (tenant_id, status, capability_code),
    CONSTRAINT fk_account_capability_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conversation_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    buyer_user_id VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    assigned_user_id BIGINT NULL,
    assigned_username VARCHAR(50) NULL,
    first_message_time DATETIME(3) NULL,
    first_response_time DATETIME(3) NULL,
    last_message_time DATETIME(3) NULL,
    sla_due_time DATETIME(3) NULL,
    note VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_assignment (tenant_id, xianyu_account_id, session_id),
    KEY idx_conversation_queue (tenant_id, status, priority, sla_due_time),
    KEY idx_conversation_assignee (tenant_id, assigned_user_id, status),
    CONSTRAINT fk_conversation_assignment_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_assignment_user FOREIGN KEY (assigned_user_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_operation_log
    ADD COLUMN operator_user_id BIGINT NULL AFTER tenant_id,
    ADD COLUMN operator_username VARCHAR(50) NULL AFTER operator_user_id,
    ADD KEY idx_operation_operator_time (tenant_id, operator_user_id, create_time);

ALTER TABLE merchant_task
    ADD COLUMN batch_id VARCHAR(64) NULL AFTER request_key,
    ADD COLUMN verification_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER result_json,
    ADD KEY idx_task_batch (tenant_id, batch_id, created_time);

INSERT IGNORE INTO sys_user_permission (user_id, permission_code)
SELECT id, 'menu:command-center' FROM sys_user WHERE role = 'USER';

INSERT INTO xianyu_sys_setting (tenant_id, setting_key, setting_value, setting_desc)
SELECT id, 'product_prohibited_terms', '微信收款,支付宝收款,刷单,返现诱导,站外交易', '商品发布前本地风险词，使用英文逗号分隔'
FROM sys_user owner
WHERE owner.id = owner.tenant_id
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
