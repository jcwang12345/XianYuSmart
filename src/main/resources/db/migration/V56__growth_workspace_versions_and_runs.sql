-- V6 Wave 7: 素材/货源版本、商机证据与可恢复工作流状态机。
-- 使用 LONGTEXT 保存结构化快照，保持 MySQL 5.7 兼容；业务层写入前校验 JSON。

CREATE TABLE growth_resource_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source_url VARCHAR(2000) NULL,
    source_item_id VARCHAR(128) NULL,
    source_captured_time DATETIME(3) NULL,
    authorization_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    license_type VARCHAR(64) NULL,
    license_note VARCHAR(1000) NULL,
    supplier_name VARCHAR(255) NULL,
    valid_from DATETIME(3) NULL,
    valid_until DATETIME(3) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    activation_request_id VARCHAR(64) NULL,
    activated_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_resource_version (tenant_id, resource_id, version_no),
    UNIQUE KEY uk_growth_resource_request (tenant_id, request_id),
    UNIQUE KEY uk_growth_resource_activation_request (tenant_id, activation_request_id),
    KEY idx_growth_resource_latest (tenant_id, resource_id, created_time),
    KEY idx_growth_resource_fingerprint (tenant_id, payload_fingerprint),
    KEY idx_growth_resource_validity (tenant_id, lifecycle_state, valid_until),
    CONSTRAINT fk_growth_resource_version_resource FOREIGN KEY (resource_id)
        REFERENCES merchant_resource (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_resource_goods_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(128) NOT NULL DEFAULT '',
    mapping_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    valid_from DATETIME(3) NULL,
    valid_until DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_resource_goods_mapping (tenant_id, resource_id, xianyu_account_id, xy_goods_id, sku_id),
    KEY idx_growth_resource_goods_target (tenant_id, xianyu_account_id, xy_goods_id),
    CONSTRAINT fk_growth_resource_goods_resource FOREIGN KEY (resource_id)
        REFERENCES merchant_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_resource_goods_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_search_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    search_type VARCHAR(24) NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    query_text VARCHAR(500) NOT NULL,
    filter_json LONGTEXT NULL,
    source_type VARCHAR(32) NOT NULL,
    authorization_status VARCHAR(32) NOT NULL DEFAULT 'USER_AUTHORIZED_READ',
    collection_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    sample_count INT NULL,
    reported_total INT NULL,
    duplicate_count INT NULL,
    result_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    collected_time DATETIME(3) NULL,
    expires_time DATETIME(3) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_search_request (tenant_id, request_id),
    KEY idx_growth_search_account_time (tenant_id, xianyu_account_id, created_time),
    KEY idx_growth_search_query (tenant_id, search_type, query_text(120), created_time),
    CONSTRAINT fk_growth_search_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_workflow_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_resource_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    definition_fingerprint CHAR(64) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    change_summary VARCHAR(500) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    activation_request_id VARCHAR(64) NULL,
    published_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_workflow_version (tenant_id, workflow_resource_id, version_no),
    UNIQUE KEY uk_growth_workflow_request (tenant_id, request_id),
    UNIQUE KEY uk_growth_workflow_activation_request (tenant_id, activation_request_id),
    KEY idx_growth_workflow_lifecycle (tenant_id, workflow_resource_id, lifecycle_state, created_time),
    CONSTRAINT fk_growth_workflow_version_resource FOREIGN KEY (workflow_resource_id)
        REFERENCES merchant_resource (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_workflow_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_resource_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    execution_mode VARCHAR(24) NOT NULL DEFAULT 'DRY_RUN',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    current_node_id VARCHAR(100) NULL,
    cancel_requested TINYINT NOT NULL DEFAULT 0,
    manual_dispatch TINYINT NOT NULL DEFAULT 0,
    input_json LONGTEXT NULL,
    output_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    lock_version INT NOT NULL DEFAULT 0,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    started_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_workflow_run_request (tenant_id, request_id),
    KEY idx_growth_workflow_run_queue (status, updated_time, id),
    KEY idx_growth_workflow_run_resource (tenant_id, workflow_resource_id, created_time),
    KEY idx_growth_workflow_run_account (tenant_id, xianyu_account_id, created_time),
    CONSTRAINT fk_growth_workflow_run_resource FOREIGN KEY (workflow_resource_id)
        REFERENCES merchant_resource (id) ON DELETE RESTRICT,
    CONSTRAINT fk_growth_workflow_run_version FOREIGN KEY (workflow_version_id)
        REFERENCES growth_workflow_version (id) ON DELETE RESTRICT,
    CONSTRAINT fk_growth_workflow_run_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_workflow_node_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(160) NOT NULL,
    input_json LONGTEXT NULL,
    output_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    compensation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    compensation_json LONGTEXT NULL,
    started_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_workflow_node (tenant_id, workflow_run_id, node_id),
    UNIQUE KEY uk_growth_workflow_node_idem (tenant_id, idempotency_key),
    KEY idx_growth_workflow_node_queue (tenant_id, workflow_run_id, status, sequence_no),
    CONSTRAINT fk_growth_workflow_node_run FOREIGN KEY (workflow_run_id)
        REFERENCES growth_workflow_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_workflow_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    node_run_id BIGINT NULL,
    event_type VARCHAR(48) NOT NULL,
    from_state VARCHAR(32) NULL,
    to_state VARCHAR(32) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    evidence_json LONGTEXT NULL,
    request_id VARCHAR(64) NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_growth_workflow_event_run (tenant_id, workflow_run_id, created_time, id),
    CONSTRAINT fk_growth_workflow_event_run FOREIGN KEY (workflow_run_id)
        REFERENCES growth_workflow_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_workflow_event_node FOREIGN KEY (node_run_id)
        REFERENCES growth_workflow_node_run (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE growth_workflow_action_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_json LONGTEXT NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(50) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_workflow_action_request (tenant_id, request_id),
    KEY idx_growth_workflow_action_run (tenant_id, workflow_run_id, created_time),
    CONSTRAINT fk_growth_workflow_action_run FOREIGN KEY (workflow_run_id)
        REFERENCES growth_workflow_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 历史素材、货源和工作流保留为版本 1；没有可证明来源/许可的字段必须保持 UNKNOWN。
INSERT INTO growth_resource_version
(tenant_id,resource_id,version_no,lifecycle_state,request_id,request_fingerprint,payload_fingerprint,payload_json,
 source_type,source_url,source_item_id,source_captured_time,authorization_status,license_type,license_note,
 supplier_name,valid_from,valid_until,operator_username,created_time)
SELECT r.tenant_id,r.id,1,'ACTIVE',CONCAT('LEGACY-RESOURCE-',r.tenant_id,'-',r.id),
       SHA2(CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,256),
       SHA2(CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,256),
       CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,'LEGACY_IMPORT',
       CASE WHEN JSON_VALID(r.data_json) THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.sourceUrl')),'') ELSE NULL END,
       r.xy_goods_id,r.updated_time,
       'UNKNOWN',NULL,'历史记录未保存许可证明，请补充后再用于新的发布决策',
       CASE WHEN JSON_VALID(r.data_json) THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.supplierName')),'') ELSE NULL END,
       r.created_time,NULL,'system',r.created_time
  FROM merchant_resource r
 WHERE r.resource_type IN ('MATERIAL','SUPPLY');

INSERT INTO growth_workflow_version
(tenant_id,workflow_resource_id,version_no,lifecycle_state,request_id,request_fingerprint,definition_fingerprint,
 definition_json,change_summary,operator_username,published_time,created_time)
SELECT r.tenant_id,r.id,1,'ACTIVE',CONCAT('LEGACY-WORKFLOW-',r.tenant_id,'-',r.id),
       SHA2(CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,256),
       SHA2(CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,256),
       CASE WHEN JSON_VALID(r.data_json) THEN r.data_json ELSE '{}' END,
       '由历史工作流定义迁移；首次编辑将创建新版本','system',r.updated_time,r.created_time
  FROM merchant_resource r
 WHERE r.resource_type='WORKFLOW';
