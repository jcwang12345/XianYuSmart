-- W3-BUG-005：买家 360 写入使用持久化请求记录，保证幂等重放和审计同事务提交。
CREATE TABLE xianyu_buyer_profile_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    buyer_user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_json LONGTEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_buyer_profile_request (tenant_id, request_id),
    KEY idx_buyer_profile_request_target (tenant_id, xianyu_account_id, buyer_user_id, create_time),
    CONSTRAINT fk_buyer_profile_request_account
        FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
