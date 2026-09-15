-- V6-PROD-ANALYTICS：商品 1/7/30 天买家漏斗。
-- 所有新增指标均允许 NULL；NULL 表示数据源尚未同步，不能解释为 0。

ALTER TABLE xianyu_goods_metric_daily
    ADD COLUMN exposure_uv_count BIGINT NULL AFTER exposure_count,
    ADD COLUMN inquiry_buyer_count BIGINT NULL AFTER inquiry_count,
    ADD COLUMN paid_buyer_count BIGINT NULL AFTER paid_order_count,
    ADD COLUMN completed_buyer_count BIGINT NULL AFTER paid_buyer_count,
    ADD COLUMN refund_buyer_count BIGINT NULL AFTER completed_buyer_count,
    ADD COLUMN refund_order_count BIGINT NULL AFTER refund_buyer_count,
    ADD COLUMN refund_amount DECIMAL(14,2) NULL AFTER paid_amount;
