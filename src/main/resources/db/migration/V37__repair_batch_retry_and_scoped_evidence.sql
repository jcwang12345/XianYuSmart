-- v2.3.6: repair persisted QA evidence created before retry evidence was re-armed.
-- QA_MOCK is the only channel touched: no production notification is replayed.
UPDATE xianyu_goods_batch_job job
   SET job.notification_sent = 1,
       job.notification_evidence_json = CONCAT(
           '{"event":"',
           CASE job.status
             WHEN 'SUCCEEDED' THEN 'PRODUCT_BATCH_SUCCEEDED'
             WHEN 'FAILED' THEN 'PRODUCT_BATCH_FAILED'
             ELSE 'PRODUCT_BATCH_PARTIAL'
           END,
           '","jobId":', job.id,
           ',"status":"', job.status,
           '","successCount":', job.success_count,
           ',"failedCount":', job.failed_count,
           ',"unknownCount":', job.unknown_count,
           ',"skippedCount":', job.skipped_count,
           ',"cancelledCount":', job.cancelled_count,
           ',"route":"QA_TEST_SINK","externalDispatched":false}'
       )
 WHERE job.execution_channel = 'QA_MOCK'
   AND job.status IN ('SUCCEEDED','FAILED','PARTIAL_SUCCESS','CANCELLED');

UPDATE xianyu_goods_event event
  JOIN xianyu_goods_batch_job job
    ON job.tenant_id = event.tenant_id AND job.id = event.batch_job_id
   SET event.outcome_state = job.status,
       event.after_json = job.notification_evidence_json,
       event.created_time = NOW(3)
 WHERE job.execution_channel = 'QA_MOCK'
   AND event.event_type = 'BATCH_NOTIFICATION'
   AND job.status IN ('SUCCEEDED','FAILED','PARTIAL_SUCCESS','CANCELLED');

-- The current-state event is intentionally one row per batch item. Keep it aligned
-- with the latest attempt so the successful platform request can be traced.
UPDATE xianyu_goods_event event
  JOIN xianyu_goods_batch_item item
    ON item.tenant_id = event.tenant_id AND item.id = event.batch_item_id
  JOIN xianyu_goods_batch_job job
    ON job.tenant_id = item.tenant_id AND job.id = item.batch_job_id
   SET event.outcome_state = item.outcome_state,
       event.platform_request_id = item.platform_request_id,
       event.after_json = item.result_json,
       event.error_message = item.error_message,
       event.created_time = COALESCE(item.completed_time, event.created_time)
 WHERE job.execution_channel = 'QA_MOCK'
   AND event.event_origin = 'BATCH_TASK';
