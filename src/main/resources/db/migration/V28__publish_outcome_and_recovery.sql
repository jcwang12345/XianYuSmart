-- PUB-01/02/03：发布任务明确区分平台结果、未知结果与本地补偿状态。

ALTER TABLE merchant_task
    ADD COLUMN outcome_state VARCHAR(40) NOT NULL DEFAULT 'QUEUED' AFTER verification_status,
    ADD COLUMN data_source VARCHAR(32) NULL AFTER outcome_state,
    ADD COLUMN platform_response_code VARCHAR(100) NULL AFTER data_source,
    ADD COLUMN recovery_hint VARCHAR(1000) NULL AFTER platform_response_code,
    ADD KEY idx_task_outcome (tenant_id, task_type, outcome_state, created_time);
