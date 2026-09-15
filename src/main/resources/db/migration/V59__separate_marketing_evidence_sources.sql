-- PRD-07 truthfulness follow-up: local intent and platform observation must
-- retain independent provenance. A local draft must never overwrite the
-- source attached to the last platform observation.

ALTER TABLE xianyu_goods_marketing_state
    ADD COLUMN desired_data_source VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER data_source,
    ADD COLUMN platform_data_source VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER desired_data_source;

UPDATE xianyu_goods_marketing_state
SET desired_data_source = CASE
        WHEN desired_fan_all_price IS NOT NULL
          OR desired_fan_old_price IS NOT NULL
          OR desired_fan_buyer_price IS NOT NULL
          OR desired_bargain_enabled IS NOT NULL
          OR desired_coin_enabled IS NOT NULL THEN 'LOCAL_DRAFT'
        ELSE 'NONE'
    END,
    platform_data_source = CASE
        WHEN platform_synced_at IS NULL THEN 'NONE'
        WHEN data_source = 'QA_FIXTURE' THEN 'QA_FIXTURE'
        ELSE 'LEGACY_SYNC_EVIDENCE'
    END;
