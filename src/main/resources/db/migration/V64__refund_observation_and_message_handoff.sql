-- B07: local observation identity is not a fabricated platform refund ID.
ALTER TABLE xianyu_refund_case
    ADD COLUMN observation_key VARCHAR(191) NULL AFTER platform_refund_id,
    ADD COLUMN local_handling_status VARCHAR(24) NOT NULL DEFAULT 'UNHANDLED',
    ADD COLUMN local_handling_note VARCHAR(1000) NULL,
    ADD COLUMN local_platform_checked TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN local_handled_by BIGINT NULL,
    ADD COLUMN local_handled_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_refund_observation (tenant_id, xianyu_account_id, observation_key);

ALTER TABLE xianyu_ai_handoff_task
    ADD COLUMN blocks_auto_reply TINYINT NOT NULL DEFAULT 1 AFTER status;
