-- V5-NTF-01/03：把业务通知事件、渠道 outbox 与每次投递结果串成一条可追踪链路。
ALTER TABLE xianyu_notification_log
    ADD COLUMN event_id VARCHAR(64) NULL AFTER channel_id,
    ADD COLUMN outbox_id BIGINT NULL AFTER event_id,
    ADD COLUMN delivery_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' AFTER send_status,
    ADD KEY idx_notification_log_event_id (tenant_id, event_id, create_time),
    ADD KEY idx_notification_log_outbox (tenant_id, outbox_id, create_time);

ALTER TABLE xianyu_notification_outbox
    ADD KEY idx_notification_outbox_event_id (tenant_id, event_id, id);
