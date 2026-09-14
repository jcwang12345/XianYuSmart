-- v2.3.6 follow-up: successful QA items must not retain failure facts from an earlier attempt.
-- This is limited to the isolated QA execution channel and never changes platform tasks.
UPDATE xianyu_goods_batch_item item
  JOIN xianyu_goods_batch_job job
    ON job.tenant_id = item.tenant_id AND job.id = item.batch_job_id
   SET item.error_code = NULL,
       item.error_message = NULL,
       item.next_retry_time = NULL,
       item.claimed_by = NULL,
       item.claimed_time = NULL
 WHERE job.execution_channel = 'QA_MOCK'
   AND item.status = 'SUCCEEDED';
