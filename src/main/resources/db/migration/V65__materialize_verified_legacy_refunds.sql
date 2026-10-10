-- Materialize existing, verified seller-side platform status snapshots.
-- This is not a fresh platform sync and does not submit/approve/refund an order or send a notification.
-- Amount, reason, deadline and platform refund ID remain NULL when unknown.
INSERT INTO xianyu_refund_case
    (tenant_id,xianyu_account_id,order_record_id,order_id,observation_key,platform_status,
     source,sync_status,coverage_status,evidence_json,synced_at,last_request_id)
SELECT orders.tenant_id,orders.xianyu_account_id,orders.id,orders.order_id,
       CONCAT('ORDER:',orders.order_id),
       IF(orders.platform_trade_status='REFUNDED','REFUNDED',orders.refund_status),
       'LOCAL_EVENT','PARTIAL','PARTIAL',
       JSON_OBJECT('source','LEGACY_PLATFORM_STATUS_SNAPSHOT','orderId',orders.order_id,
                   'platformTradeStatus',orders.platform_trade_status,
                   'notice','历史平台状态快照，未重新请求平台；原因、金额、截止时间未知'),
       orders.platform_status_checked_at,CONCAT('b07-legacy-refund-',orders.id)
FROM xianyu_goods_order orders
JOIN xianyu_account account ON account.id=orders.xianyu_account_id AND account.tenant_id=orders.tenant_id
WHERE orders.refund_status IN ('REQUESTED','PROCESSING','REFUNDED')
  AND orders.platform_trade_status IN ('REFUNDING','REFUNDED')
  AND orders.order_id IS NOT NULL AND orders.order_id<>''
  AND account.unb IS NOT NULL AND account.unb<>''
  AND orders.buyer_user_id IS NOT NULL AND orders.buyer_user_id<>'' AND orders.buyer_user_id<>account.unb
  AND NOT EXISTS (SELECT 1 FROM xianyu_refund_case existing
                   WHERE existing.tenant_id=orders.tenant_id AND existing.xianyu_account_id=orders.xianyu_account_id
                     AND existing.order_record_id=orders.id);

INSERT IGNORE INTO xianyu_order_event
    (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_origin,outcome_state,
     data_source,request_id,idempotency_key,after_json)
SELECT tenant_id,xianyu_account_id,order_record_id,order_id,'REFUND_LEGACY_OBSERVED','SYSTEM','PARTIAL',
       'LOCAL_EVENT',last_request_id,last_request_id,evidence_json
FROM xianyu_refund_case WHERE last_request_id=CONCAT('b07-legacy-refund-',order_record_id);
