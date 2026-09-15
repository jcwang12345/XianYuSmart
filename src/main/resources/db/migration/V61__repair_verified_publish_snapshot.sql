-- V6-W18-QA-003：历史发布事件已经完成字段核对时，把可验证的平台事实补回商品主档。
-- 只填充主档缺失值；绝不覆盖后来同步到的库存、类目或图片。

UPDATE xianyu_goods goods
JOIN xianyu_goods_event event
  ON event.tenant_id = goods.tenant_id
 AND event.xianyu_account_id = goods.xianyu_account_id
 AND event.xy_goods_id = goods.xy_good_id
 AND event.event_type = 'PUBLISH'
LEFT JOIN xianyu_goods_event newer
  ON newer.tenant_id = event.tenant_id
 AND newer.xianyu_account_id = event.xianyu_account_id
 AND newer.xy_goods_id = event.xy_goods_id
 AND newer.event_type = 'PUBLISH'
 AND newer.id > event.id
SET goods.stock = COALESCE(
        goods.stock,
        CAST(JSON_UNQUOTE(JSON_EXTRACT(event.after_json, '$.platformReadBack.stock')) AS SIGNED)
    ),
    goods.category_id = COALESCE(
        NULLIF(goods.category_id, ''),
        JSON_UNQUOTE(JSON_EXTRACT(event.after_json, '$.platformReadBack.categoryId'))
    ),
    goods.category_name = COALESCE(
        NULLIF(goods.category_name, ''),
        JSON_UNQUOTE(JSON_EXTRACT(event.after_json, '$.platformReadBack.categoryName'))
    ),
    goods.product_source = 'SYSTEM_PUBLISH',
    goods.sync_status = 'SUCCEEDED',
    goods.last_sync_request_id = COALESCE(goods.last_sync_request_id, event.request_id),
    goods.last_synced_time = COALESCE(goods.last_synced_time, event.created_time),
    goods.updated_time = CURRENT_TIMESTAMP(3)
WHERE newer.id IS NULL
  AND JSON_VALID(event.after_json)
  AND JSON_UNQUOTE(JSON_EXTRACT(event.after_json, '$.verificationStatus')) = 'VERIFIED'
  AND JSON_UNQUOTE(JSON_EXTRACT(event.after_json, '$.localSynced')) = 'true';
