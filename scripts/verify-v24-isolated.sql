-- Isolated MySQL 5.7 migration/state-machine smoke test. Never run against production.
USE xianyusmart_v24_check_20260913;
DROP PROCEDURE IF EXISTS v24_assert;
DELIMITER //
CREATE PROCEDURE v24_assert(IN ok BOOLEAN, IN note VARCHAR(200))
BEGIN
 IF DATABASE() <> 'xianyusmart_v24_check_20260913' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Wrong database'; END IF;
 IF ok IS NULL OR NOT ok THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=note; END IF;
END//
DELIMITER ;
CALL v24_assert(TRUE,'isolated database');
START TRANSACTION;
INSERT INTO xianyu_account(id,tenant_id,unb,status) VALUES(900001,900001,'fixture-seller',1);
INSERT INTO xianyu_goods_order(id,tenant_id,xianyu_account_id,xy_goods_id,pnm_id,order_id,buyer_user_id,state,delivery_status,attempt_count)
VALUES(900001,900001,900001,'fixture-goods','fixture-message','fixture-order','fixture-buyer',0,'PENDING',0);
UPDATE xianyu_goods_order SET delivery_status='PROCESSING', lease_owner='old-claim', lease_expire_time=DATE_ADD(NOW(3), INTERVAL 120 SECOND), external_attempt_started=0 WHERE id=900001 AND state<>1 AND delivery_status IN ('PENDING','FAILED','RETRY_WAIT') AND (lease_owner IS NULL OR lease_expire_time<NOW(3));
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'manual claim');
UPDATE xianyu_goods_order SET external_attempt_started=1 WHERE id=900001 AND lease_owner='wrong-claim' AND lease_expire_time>NOW(3) AND delivery_status='PROCESSING';
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=0,'stale sender rejected');
UPDATE xianyu_goods_order SET external_attempt_started=1 WHERE id=900001 AND lease_owner='old-claim' AND lease_expire_time>NOW(3) AND delivery_status='PROCESSING';
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'persist external marker');
UPDATE xianyu_goods_order SET lease_expire_time=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=900001;
UPDATE xianyu_goods_order SET delivery_status='REVIEW_REQUIRED', state=-1, last_error_code='DELIVERY_UNCERTAIN', last_error_message='外发开始后任务中断，请核对订单及聊天', fail_reason='外发开始后任务中断，请核对订单及聊天', next_retry_time=NULL, lease_owner=NULL, lease_expire_time=NULL, delivery_message_next_retry_time=NULL, exception_revision=exception_revision+1 WHERE delivery_status='PROCESSING' AND lease_expire_time < NOW(3) AND external_attempt_started=1;
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'expired external task enters review');
UPDATE xianyu_goods_order SET state=1, content=COALESCE('fixture-content',content), fail_reason=NULL, delivery_status=CASE WHEN 'COMPLETED'='FAILED' AND attempt_count<3 THEN 'RETRY_WAIT' ELSE 'COMPLETED' END, last_error_message=NULL, next_retry_time=CASE WHEN 'COMPLETED'='FAILED' AND attempt_count<3 THEN DATE_ADD(NOW(3), INTERVAL 60 SECOND) ELSE NULL END, lease_owner=NULL, lease_expire_time=NULL, exception_revision=exception_revision+IF(1=-1,1,0) WHERE id=900001 AND lease_owner='old-claim' AND lease_expire_time>NOW(3);
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=0,'stale completion rejected');
CALL v24_assert((SELECT delivery_status='REVIEW_REQUIRED' FROM xianyu_goods_order WHERE id=900001),'unknown not retry');
INSERT INTO xianyu_order_confirmation(tenant_id,xianyu_account_id,order_id) VALUES(900001,900001,'fixture-order') ON DUPLICATE KEY UPDATE id=id;
INSERT INTO xianyu_order_confirmation(tenant_id,xianyu_account_id,order_id) VALUES(900001,900001,'fixture-order') ON DUPLICATE KEY UPDATE id=id;
CALL v24_assert((SELECT COUNT(*)=1 FROM xianyu_order_confirmation WHERE xianyu_account_id=900001),'confirmation idempotent');
-- Exercise a nonempty cross-table join: historical tables may use a different collation.
SELECT c.id FROM xianyu_order_confirmation c JOIN xianyu_goods_order o
ON o.tenant_id=c.tenant_id AND o.xianyu_account_id=c.xianyu_account_id AND CAST(o.order_id AS BINARY)=CAST(c.order_id AS BINARY);
INSERT INTO xianyu_notification_channel(id,tenant_id,channel_name,webhook_url,event_types)
VALUES(900001,900001,'fixture','https://example.invalid/fixture','CREDENTIAL_EXPIRED');
INSERT INTO xianyu_notification_outbox(tenant_id,channel_id,event_type,xianyu_account_id,dedupe_key,event_id,title,content,data_json,status,lease_owner)
VALUES(900001,900001,'CREDENTIAL_EXPIRED',900001,'fixture','fixture','fixture','fixture','{}','PROCESSING','fixture');
UPDATE xianyu_notification_outbox SET status='FAILED',next_retry_time=COALESCE(NULL,NOW(3)),lease_owner=NULL
WHERE event_id='fixture' AND tenant_id=900001;
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'terminal notification preserves non-null retry timestamp');
INSERT INTO xianyu_goods_auto_reply_record(id,tenant_id,xianyu_account_id,xy_goods_id,pnm_id,state,scheduled_time)
VALUES(900001,900001,900001,'fixture-goods','fixture-reply',0,NOW(3));
UPDATE xianyu_goods_auto_reply_record SET state = 2, lease_owner = 'reply-claim', lease_expire_time = DATE_ADD(NOW(3), INTERVAL 600 SECOND), attempt_count = attempt_count + 1 WHERE id = 900001 AND external_attempt_started=0 AND ((state = 0 AND scheduled_time<=NOW(3) AND (next_retry_time IS NULL OR next_retry_time<=NOW(3))) OR (state = 2 AND lease_expire_time < NOW(3)));
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'reply claim');
UPDATE xianyu_goods_auto_reply_record SET state=IF(external_attempt_started=1,3,IF(attempt_count<3,0,-1)),next_retry_time=DATE_ADD(NOW(3),INTERVAL 60 SECOND),lease_owner=NULL,lease_expire_time=NULL,last_error_code=IF(external_attempt_started=1,'REPLY_UNCERTAIN','REPLY_PREPARATION_FAILED'),last_error_message='fixture',exception_revision=exception_revision+1 WHERE id=900001 AND state=2 AND lease_owner='wrong-token';
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=0,'stale reply failed update rejected');
UPDATE xianyu_goods_auto_reply_record SET external_attempt_started=1 WHERE id=900001 AND state=2 AND lease_expire_time>NOW(3) AND lease_owner='reply-claim';
SET @affected=ROW_COUNT(); CALL v24_assert(@affected=1,'reply starts');
UPDATE xianyu_goods_auto_reply_record SET state=IF(external_attempt_started=1,3,IF(attempt_count<3,0,-1)),next_retry_time=DATE_ADD(NOW(3),INTERVAL 60 SECOND),lease_owner=NULL,lease_expire_time=NULL,last_error_code=IF(external_attempt_started=1,'REPLY_UNCERTAIN','REPLY_PREPARATION_FAILED'),last_error_message='fixture unknown',exception_revision=exception_revision+1 WHERE id=900001 AND state=2 AND lease_owner='reply-claim';
CALL v24_assert((SELECT state=3 FROM xianyu_goods_auto_reply_record WHERE id=900001),'unknown reply is not resent');
ROLLBACK;
DROP PROCEDURE v24_assert;
SELECT 'V24 state-machine assertions passed' AS result;
