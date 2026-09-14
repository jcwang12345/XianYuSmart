-- XYM-IM-002 / IM04-05：商品知识按版本和有效时间管理；过期内容不能进入 AI 回复。

CREATE TABLE xianyu_goods_knowledge_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    version_no INT NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    effective_time DATETIME(3) NOT NULL,
    expires_time DATETIME(3) NULL,
    activated_time DATETIME(3) NULL,
    invalidated_time DATETIME(3) NULL,
    created_by BIGINT NULL,
    created_username VARCHAR(50) NULL,
    request_id VARCHAR(80) NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_knowledge_version (tenant_id,xianyu_account_id,xy_goods_id,version_no),
    UNIQUE KEY uk_goods_knowledge_request (tenant_id,request_id),
    KEY idx_goods_knowledge_effective (tenant_id,xianyu_account_id,xy_goods_id,status,effective_time,expires_time),
    CONSTRAINT fk_goods_knowledge_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_goods_knowledge_user FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_goods_knowledge_action_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_knowledge_action_request (tenant_id,request_id),
    KEY idx_goods_knowledge_action_version (tenant_id,knowledge_version_id,created_time),
    CONSTRAINT fk_goods_knowledge_action_version FOREIGN KEY (knowledge_version_id)
        REFERENCES xianyu_goods_knowledge_version(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_goods_config
    ADD COLUMN active_knowledge_version_id BIGINT NULL AFTER fixed_material,
    ADD KEY idx_goods_config_knowledge (tenant_id,active_knowledge_version_id),
    ADD CONSTRAINT fk_goods_config_knowledge FOREIGN KEY (active_knowledge_version_id)
        REFERENCES xianyu_goods_knowledge_version(id) ON DELETE SET NULL;

ALTER TABLE xianyu_goods_auto_reply_record
    ADD COLUMN knowledge_version_id BIGINT NULL AFTER handoff_reason_code,
    ADD COLUMN knowledge_version_no INT NULL AFTER knowledge_version_id,
    ADD KEY idx_reply_knowledge_version (tenant_id,knowledge_version_id),
    ADD CONSTRAINT fk_reply_knowledge_version FOREIGN KEY (knowledge_version_id)
        REFERENCES xianyu_goods_knowledge_version(id) ON DELETE SET NULL;

INSERT INTO xianyu_goods_knowledge_version
    (tenant_id,xianyu_account_id,xy_goods_id,version_no,content,source_type,status,effective_time,
     activated_time,created_username,request_id)
SELECT tenant_id,xianyu_account_id,xy_goods_id,1,fixed_material,'LEGACY_IMPORT','ACTIVE',NOW(3),NOW(3),
       'migration',CONCAT('migration-v45-',tenant_id,'-',id)
  FROM xianyu_goods_config
 WHERE fixed_material IS NOT NULL AND TRIM(fixed_material)<>'';

UPDATE xianyu_goods_config config
JOIN xianyu_goods_knowledge_version knowledge
  ON knowledge.tenant_id=config.tenant_id
 AND knowledge.xianyu_account_id=config.xianyu_account_id
 AND knowledge.xy_goods_id=config.xy_goods_id
 AND knowledge.version_no=1
 AND knowledge.source_type='LEGACY_IMPORT'
SET config.active_knowledge_version_id=knowledge.id;
