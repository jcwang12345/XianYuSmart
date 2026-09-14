-- XYM-IM-001：不要依赖 MySQL Connector 的 affected-row 语义判断首次创建与幂等重放。

ALTER TABLE xianyu_ai_handoff_task
    ADD COLUMN open_attempt_token VARCHAR(64) NULL AFTER request_id;
