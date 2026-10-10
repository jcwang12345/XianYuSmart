CREATE TABLE xianyu_account_local_profile (
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    operating_tags_json LONGTEXT NULL,
    auto_reply_allowed TINYINT NOT NULL DEFAULT 1,
    auto_delivery_allowed TINYINT NOT NULL DEFAULT 1,
    archived_from_state VARCHAR(16) NULL,
    archived_at DATETIME(3) NULL,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, xianyu_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO xianyu_account_local_profile (tenant_id, xianyu_account_id)
SELECT tenant_id, id FROM xianyu_account;

-- Commands and audit must survive physical removal of an empty account.
CREATE TABLE xianyu_account_local_command (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_local_command_request (tenant_id, request_id),
    KEY idx_account_local_command_account (tenant_id, xianyu_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
