-- A payment message can be replayed after a WebSocket reconnect. The order unique
-- keys already prevent duplicate delivery tasks, but notification dispatch also
-- needs a durable, atomic claim so the replay cannot alert operators again.
ALTER TABLE xianyu_goods_order
    ADD COLUMN order_created_notified TINYINT NOT NULL DEFAULT 0 AFTER last_error_message;

-- Every existing row predates this claim mechanism and may already have emitted a
-- notification. Mark it claimed so deploying the fix never re-notifies old orders.
UPDATE xianyu_goods_order
SET order_created_notified = 1;
