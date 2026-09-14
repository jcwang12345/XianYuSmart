-- IM-03/05：AI 无法安全回复时进入持久化人工队列，并保存可解释决策证据。

ALTER TABLE xianyu_goods_auto_reply_record
    ADD COLUMN decision_state VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER state,
    ADD COLUMN confidence_score DECIMAL(7,6) NULL AFTER decision_state,
    ADD COLUMN model_name VARCHAR(100) NULL AFTER confidence_score,
    ADD COLUMN processing_duration_ms BIGINT NULL AFTER model_name,
    ADD COLUMN handoff_reason_code VARCHAR(40) NULL AFTER processing_duration_ms,
    ADD KEY idx_reply_decision (tenant_id, decision_state, create_time);

CREATE TABLE xianyu_ai_handoff_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    xy_goods_id VARCHAR(100) NULL,
    buyer_user_id VARCHAR(100) NULL,
    source_reply_record_id BIGINT NULL,
    reason_code VARCHAR(40) NOT NULL,
    reason_detail VARCHAR(500) NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'HIGH',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    confidence_score DECIMAL(7,6) NULL,
    model_name VARCHAR(100) NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    claimed_by BIGINT NULL,
    claimed_username VARCHAR(50) NULL,
    claimed_time DATETIME(3) NULL,
    resolved_by BIGINT NULL,
    resolved_username VARCHAR(50) NULL,
    resolved_time DATETIME(3) NULL,
    resolution_note VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_handoff_dedupe (tenant_id, dedupe_key),
    KEY idx_ai_handoff_queue (tenant_id, status, priority, created_time),
    KEY idx_ai_handoff_account (tenant_id, xianyu_account_id, status, created_time),
    KEY idx_ai_handoff_session (tenant_id, xianyu_account_id, session_id, created_time),
    CONSTRAINT fk_ai_handoff_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_handoff_reply FOREIGN KEY (source_reply_record_id) REFERENCES xianyu_goods_auto_reply_record (id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_handoff_claimed_user FOREIGN KEY (claimed_by) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_handoff_resolved_user FOREIGN KEY (resolved_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conversation_assignment
    ADD COLUMN handoff_status VARCHAR(20) NOT NULL DEFAULT 'NONE' AFTER auto_reply_state,
    ADD COLUMN handoff_reason_code VARCHAR(40) NULL AFTER handoff_status,
    ADD COLUMN handoff_task_id BIGINT NULL AFTER handoff_reason_code,
    ADD COLUMN handoff_created_time DATETIME(3) NULL AFTER handoff_task_id,
    ADD KEY idx_conversation_handoff (tenant_id, handoff_status, handoff_created_time),
    ADD CONSTRAINT fk_conversation_handoff_task FOREIGN KEY (handoff_task_id) REFERENCES xianyu_ai_handoff_task (id) ON DELETE SET NULL;
