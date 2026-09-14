-- XYM-IM-004：商品知识保存必须区分精确重放和异载荷 requestId 复用。

ALTER TABLE xianyu_goods_knowledge_version
    ADD COLUMN request_payload_hash CHAR(64) NOT NULL DEFAULT 'LEGACY' AFTER request_id,
    ADD COLUMN request_attempt_token VARCHAR(64) NOT NULL DEFAULT 'LEGACY' AFTER request_payload_hash;
