-- XYM-PRD-005：持久化执行通道与通知证据。生产任务默认且始终使用 PLATFORM。
ALTER TABLE xianyu_goods_batch_job
    ADD COLUMN execution_channel VARCHAR(24) NOT NULL DEFAULT 'PLATFORM' AFTER operation_params_json,
    ADD COLUMN notification_evidence_json LONGTEXT NULL AFTER notification_sent,
    ADD KEY idx_goods_batch_channel (tenant_id, execution_channel, created_time);
