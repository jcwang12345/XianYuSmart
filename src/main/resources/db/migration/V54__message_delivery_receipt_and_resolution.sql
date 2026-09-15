-- V6-IM-02：消息发送尝试必须绑定完整请求指纹，并为“结果未知”提供人工核对闭环。
-- 旧记录无法反推完整请求，统一标记 LEGACY；新代码只对新记录执行严格载荷校验。

ALTER TABLE xianyu_message_send_attempt
    ADD COLUMN request_payload_hash CHAR(64) NOT NULL DEFAULT 'LEGACY' AFTER idempotency_key,
    ADD COLUMN attempt_token VARCHAR(64) NULL AFTER request_payload_hash,
    ADD COLUMN event_id VARCHAR(64) NULL AFTER attempt_token,
    ADD COLUMN platform_receipt_json TEXT NULL AFTER platform_ack_code,
    ADD COLUMN resolution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER error_message,
    ADD COLUMN resolution_note VARCHAR(1000) NULL AFTER resolution_status,
    ADD COLUMN verified_message_id VARCHAR(128) NULL AFTER resolution_note,
    ADD COLUMN resolution_request_id VARCHAR(80) NULL AFTER verified_message_id,
    ADD COLUMN resolution_payload_hash CHAR(64) NULL AFTER resolution_request_id,
    ADD COLUMN resolved_by BIGINT NULL AFTER resolution_payload_hash,
    ADD COLUMN resolved_username VARCHAR(50) NULL AFTER resolved_by,
    ADD COLUMN resolved_time DATETIME(3) NULL AFTER resolved_username,
    ADD UNIQUE KEY uk_message_attempt_token (tenant_id, attempt_token),
    ADD UNIQUE KEY uk_message_attempt_event (tenant_id, event_id),
    ADD UNIQUE KEY uk_message_resolution_request (tenant_id, resolution_request_id),
    ADD CONSTRAINT fk_message_attempt_resolver FOREIGN KEY (resolved_by) REFERENCES sys_user (id) ON DELETE SET NULL;

UPDATE xianyu_message_send_attempt
   SET resolution_status=CASE WHEN outcome_state='UNKNOWN' THEN 'PENDING_VERIFICATION' ELSE 'NOT_REQUIRED' END
 WHERE resolution_status='NOT_REQUIRED';

ALTER TABLE xianyu_welcome_claim
    ADD COLUMN source_message_id VARCHAR(128) NULL AFTER buyer_user_id,
    ADD COLUMN last_request_id VARCHAR(80) NULL AFTER status,
    ADD COLUMN resolution_note VARCHAR(500) NULL AFTER last_request_id,
    ADD COLUMN update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER create_time,
    ADD KEY idx_welcome_status (tenant_id, status, update_time);

-- V6-AI-06：关键词规则具备显式优先级、匹配类型、有效期和内容版本。
ALTER TABLE xianyu_keyword_reply_rule
    ADD COLUMN match_type VARCHAR(16) NOT NULL DEFAULT 'CONTAINS' AFTER match_mode,
    ADD COLUMN priority INT NOT NULL DEFAULT 100 AFTER match_type,
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 AFTER priority,
    ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER enabled,
    ADD COLUMN effective_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER version_no,
    ADD COLUMN expires_time DATETIME(3) NULL AFTER effective_time,
    ADD COLUMN update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER create_time,
    ADD KEY idx_keyword_effective (tenant_id, enabled, effective_time, expires_time, priority);

UPDATE xianyu_keyword_reply_rule
   SET match_type=CASE WHEN match_mode=2 THEN 'EXACT' WHEN match_mode=3 THEN 'REGEX' ELSE 'CONTAINS' END;

ALTER TABLE xianyu_keyword_reply_content
    ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER reply_image_url,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER version_no,
    ADD COLUMN effective_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER status,
    ADD COLUMN expires_time DATETIME(3) NULL AFTER effective_time,
    ADD COLUMN update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER create_time;

CREATE TABLE xianyu_keyword_reply_rule_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    match_type VARCHAR(16) NOT NULL,
    priority INT NOT NULL,
    enabled TINYINT NOT NULL,
    is_fallback TINYINT NOT NULL,
    sharing_scope VARCHAR(16) NOT NULL,
    account_ids_json TEXT NOT NULL,
    contents_json LONGTEXT NOT NULL,
    effective_time DATETIME(3) NOT NULL,
    expires_time DATETIME(3) NULL,
    request_id VARCHAR(80) NOT NULL,
    request_payload_hash CHAR(64) NOT NULL,
    created_by BIGINT NULL,
    created_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_keyword_version (tenant_id, rule_id, version_no),
    UNIQUE KEY uk_keyword_version_request (tenant_id, request_id),
    KEY idx_keyword_version_account (tenant_id, xianyu_account_id, rule_id),
    -- 历史版本是审计证据，规则停用后仍必须保留，禁止级联删除。
    CONSTRAINT fk_keyword_version_rule FOREIGN KEY (rule_id) REFERENCES xianyu_keyword_reply_rule (id) ON DELETE RESTRICT,
    CONSTRAINT fk_keyword_version_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_keyword_version_user FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_goods_auto_reply_record
    ADD COLUMN selected_rule_id BIGINT NULL AFTER matched_keyword,
    ADD COLUMN selected_content_id BIGINT NULL AFTER selected_rule_id,
    ADD COLUMN decision_trace_json LONGTEXT NULL AFTER decision_state,
    ADD COLUMN safety_verdict VARCHAR(32) NULL AFTER decision_trace_json;

CREATE TABLE xianyu_reply_policy_simulation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    buyer_user_id VARCHAR(100) NULL,
    session_id VARCHAR(100) NULL,
    buyer_message TEXT NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_payload_hash CHAR(64) NOT NULL,
    selected_strategy VARCHAR(32) NOT NULL,
    selected_rule_id BIGINT NULL,
    knowledge_version_id BIGINT NULL,
    knowledge_version_no INT NULL,
    safety_verdict VARCHAR(32) NOT NULL,
    handoff_reason_code VARCHAR(40) NULL,
    answer_preview TEXT NULL,
    decision_trace_json LONGTEXT NOT NULL,
    would_send TINYINT NOT NULL DEFAULT 0,
    platform_write TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    created_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reply_simulation_request (tenant_id, request_id),
    KEY idx_reply_simulation_account (tenant_id, xianyu_account_id, created_time),
    CONSTRAINT fk_reply_simulation_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_reply_simulation_user FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
