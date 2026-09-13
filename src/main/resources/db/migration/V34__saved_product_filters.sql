-- PRD P2：用户级保存筛选器。筛选条件是本地偏好，不影响平台状态。
CREATE TABLE xianyu_saved_filter (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    filter_type VARCHAR(32) NOT NULL,
    filter_name VARCHAR(100) NOT NULL,
    filter_json TEXT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_saved_filter_name(tenant_id,user_id,filter_type,filter_name),
    UNIQUE KEY uk_saved_filter_request(tenant_id,request_id),
    KEY idx_saved_filter_user(tenant_id,user_id,filter_type,updated_time),
    CONSTRAINT fk_saved_filter_user FOREIGN KEY(user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
