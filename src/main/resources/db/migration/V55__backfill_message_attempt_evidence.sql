-- V6-IM-02：V54 之前产生的发送尝试也必须能够在人工核对界面中稳定定位。
-- 历史记录无法取得平台原始事件号，因此生成带 LEGACY 标识的本地唯一证据号；
-- 新记录仍由业务代码在发送前生成真实 attempt_token / event_id。

UPDATE xianyu_message_send_attempt
   SET attempt_token = CONCAT('LEGACY-ATTEMPT-', tenant_id, '-', id)
 WHERE attempt_token IS NULL OR attempt_token = '';

UPDATE xianyu_message_send_attempt
   SET event_id = CONCAT('LEGACY-EVENT-', tenant_id, '-', id)
 WHERE event_id IS NULL OR event_id = '';

ALTER TABLE xianyu_message_send_attempt
    MODIFY COLUMN attempt_token VARCHAR(64) NOT NULL,
    MODIFY COLUMN event_id VARCHAR(64) NOT NULL;
