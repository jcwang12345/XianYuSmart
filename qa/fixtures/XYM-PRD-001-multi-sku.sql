-- XYM-PRD-001 / P02-02, P02-11 isolated-QA fixture.
-- Idempotent and intentionally limited to tenant 1 / account 101 / QA-GOODS-*.
-- Never load this file into a production database.

UPDATE xianyu_goods
   SET sku_count=4, stock=26, sync_status='SUCCEEDED', coverage_status='FULL',
       last_sync_error_code=NULL, last_sync_error_message=NULL,
       last_synced_time=NOW(3), last_sync_request_id='qa-xym-prd-001-4sku'
 WHERE tenant_id=1 AND xianyu_account_id=101 AND xy_good_id='QA-GOODS-0864';

INSERT INTO xianyu_goods_sku
  (id,tenant_id,xy_goods_id,sku_id,price,quantity,property_text,property_id,value_id,
   value_text,property_sort_order,value_sort_order,features,xianyu_account_id,display_name)
VALUES
  ('QA-SKU-0864-01',1,'QA-GOODS-0864','0864-01',1990,8,'授权类型：个人版；期限：7天',101,1011,'个人版 / 7天',1,1,JSON_OBJECT('platformStatus','ON_SALE','originalPrice',29.90,'image',NULL),101,'个人版 / 7天'),
  ('QA-SKU-0864-02',1,'QA-GOODS-0864','0864-02',2990,7,'授权类型：个人版；期限：30天',101,1012,'个人版 / 30天',1,2,JSON_OBJECT('platformStatus','ON_SALE','originalPrice',39.90,'image',NULL),101,'个人版 / 30天'),
  ('QA-SKU-0864-03',1,'QA-GOODS-0864','0864-03',3990,6,'授权类型：团队版；期限：7天',101,1013,'团队版 / 7天',1,3,JSON_OBJECT('platformStatus','ON_SALE','originalPrice',49.90,'image',NULL),101,'团队版 / 7天'),
  ('QA-SKU-0864-04',1,'QA-GOODS-0864','0864-04',5990,5,'授权类型：团队版；期限：30天',101,1014,'团队版 / 30天',1,4,JSON_OBJECT('platformStatus','OFF_SHELF','originalPrice',69.90,'image',NULL),101,'团队版 / 30天')
ON DUPLICATE KEY UPDATE
  price=VALUES(price),quantity=VALUES(quantity),property_text=VALUES(property_text),
  value_text=VALUES(value_text),features=VALUES(features),xianyu_account_id=VALUES(xianyu_account_id),
  display_name=VALUES(display_name);

INSERT INTO xianyu_goods_sku_property
  (id,tenant_id,xy_goods_id,property_id,property_text,property_sort_order,value_id,value_text,value_sort_order,xianyu_account_id)
VALUES
  ('QA-SP-0864-01',1,'QA-GOODS-0864',101,'授权组合',1,1011,'个人版 / 7天',1,101),
  ('QA-SP-0864-02',1,'QA-GOODS-0864',101,'授权组合',1,1012,'个人版 / 30天',2,101),
  ('QA-SP-0864-03',1,'QA-GOODS-0864',101,'授权组合',1,1013,'团队版 / 7天',3,101),
  ('QA-SP-0864-04',1,'QA-GOODS-0864',101,'授权组合',1,1014,'团队版 / 30天',4,101)
ON DUPLICATE KEY UPDATE value_text=VALUES(value_text),value_sort_order=VALUES(value_sort_order),xianyu_account_id=VALUES(xianyu_account_id);

INSERT INTO xianyu_goods_auto_delivery_config
  (tenant_id,xianyu_account_id,xianyu_goods_id,xy_goods_id,delivery_mode,sku_id,sku_name,
   kami_config_ids,kami_delivery_template,delivery_message_template,auto_confirm_shipment,rag_delay_seconds)
SELECT 1,101,100864,'QA-GOODS-0864',2,sku_id,display_name,
       JSON_ARRAY(9100 + value_sort_order),'卡密：{deliveryContent}',
       'QA 隔离履约映射：{deliveryContent}',0,15
  FROM xianyu_goods_sku
 WHERE tenant_id=1 AND xianyu_account_id=101 AND xy_goods_id='QA-GOODS-0864'
ON DUPLICATE KEY UPDATE
  sku_name=VALUES(sku_name),kami_config_ids=VALUES(kami_config_ids),
  kami_delivery_template=VALUES(kami_delivery_template),delivery_message_template=VALUES(delivery_message_template);

UPDATE xianyu_goods
   SET sku_count=50, stock=1275, sync_status='SUCCEEDED', coverage_status='FULL',
       last_sync_error_code=NULL, last_sync_error_message=NULL,
       last_synced_time=NOW(3), last_sync_request_id='qa-xym-prd-001-50sku'
 WHERE tenant_id=1 AND xianyu_account_id=101 AND xy_good_id='QA-GOODS-0960';

INSERT INTO xianyu_goods_sku
  (id,tenant_id,xy_goods_id,sku_id,price,quantity,property_text,property_id,value_id,
   value_text,property_sort_order,value_sort_order,features,xianyu_account_id,display_name)
SELECT CONCAT('QA-SKU-0960-',LPAD(n,2,'0')),1,'QA-GOODS-0960',CONCAT('0960-',LPAD(n,2,'0')),
       990+n*10,n+1,CONCAT('套餐：批量规格 ',LPAD(n+1,2,'0'),'；期限：7天'),201,2000+n,
       CONCAT('批量规格 ',LPAD(n+1,2,'0'),' / 7天'),1,n,
       JSON_OBJECT('platformStatus',IF(MOD(n,13)=0,'OFF_SHELF','ON_SALE'),'originalPrice',(1490+n*10)/100,'image',NULL),
       101,CONCAT('批量规格 ',LPAD(n+1,2,'0'),' / 7天')
  FROM (
    SELECT ones.n + tens.n*10 AS n
      FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
      CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) tens
  ) numbers
ON DUPLICATE KEY UPDATE
  price=VALUES(price),quantity=VALUES(quantity),property_text=VALUES(property_text),
  value_text=VALUES(value_text),features=VALUES(features),xianyu_account_id=VALUES(xianyu_account_id),
  display_name=VALUES(display_name);

INSERT INTO xianyu_goods_auto_delivery_config
  (tenant_id,xianyu_account_id,xianyu_goods_id,xy_goods_id,delivery_mode,sku_id,sku_name,
   kami_config_ids,kami_delivery_template,delivery_message_template,auto_confirm_shipment,rag_delay_seconds)
SELECT 1,101,100960,'QA-GOODS-0960',2,sku_id,display_name,
       JSON_ARRAY(9200 + value_sort_order),'卡密：{deliveryContent}',
       'QA 隔离长表履约映射：{deliveryContent}',0,15
  FROM xianyu_goods_sku
 WHERE tenant_id=1 AND xianyu_account_id=101 AND xy_goods_id='QA-GOODS-0960'
ON DUPLICATE KEY UPDATE
  sku_name=VALUES(sku_name),kami_config_ids=VALUES(kami_config_ids),
  kami_delivery_template=VALUES(kami_delivery_template),delivery_message_template=VALUES(delivery_message_template);

-- Verified empty-SKU control: product coverage is FULL and both counts are zero.
UPDATE xianyu_goods
   SET sku_count=0, sync_status='SUCCEEDED', coverage_status='FULL',
       last_synced_time=NOW(3), last_sync_request_id='qa-xym-prd-001-empty-sku'
 WHERE tenant_id=1 AND xianyu_account_id=101 AND xy_good_id='QA-GOODS-0000';
