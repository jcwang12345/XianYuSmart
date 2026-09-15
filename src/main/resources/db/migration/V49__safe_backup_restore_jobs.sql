-- V6-BACKUP-02/03/04/05: manifest preview, idempotent restore job and per-module restore points.
CREATE TABLE xianyu_backup_restore_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    preview_request_id VARCHAR(80) NOT NULL,
    execute_request_id VARCHAR(80) NULL,
    rollback_request_id VARCHAR(80) NULL,
    preview_token VARCHAR(64) NOT NULL,
    source_checksum CHAR(64) NOT NULL,
    selected_modules_json TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    preview_json TEXT NOT NULL,
    required_confirmation VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PREVIEWED',
    requested_by BIGINT NULL,
    executed_by BIGINT NULL,
    rolled_back_by BIGINT NULL,
    expires_time DATETIME(3) NOT NULL,
    started_time DATETIME(3) NULL,
    finished_time DATETIME(3) NULL,
    rolled_back_time DATETIME(3) NULL,
    error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_backup_preview_request (tenant_id, preview_request_id),
    UNIQUE KEY uk_backup_preview_token (tenant_id, preview_token),
    UNIQUE KEY uk_backup_execute_request (tenant_id, execute_request_id),
    UNIQUE KEY uk_backup_rollback_request (tenant_id, rollback_request_id),
    KEY idx_backup_job_status (tenant_id, status, created_time),
    CONSTRAINT fk_backup_requested_by FOREIGN KEY (requested_by) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_backup_executed_by FOREIGN KEY (executed_by) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_backup_rolled_back_by FOREIGN KEY (rolled_back_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_backup_restore_point (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    module_key VARCHAR(64) NOT NULL,
    module_name VARCHAR(100) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    snapshot_checksum CHAR(64) NOT NULL,
    record_count BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_backup_restore_point (tenant_id, job_id, module_key),
    KEY idx_backup_restore_job (tenant_id, job_id),
    CONSTRAINT fk_backup_restore_point_job FOREIGN KEY (job_id) REFERENCES xianyu_backup_restore_job (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dedicated, opt-in QA probe. It exists in every schema but no product flow writes it;
-- the handler/controller are only registered when BACKUP_QA_MOCK_ENABLED=true.
CREATE TABLE xianyu_backup_restore_probe (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    probe_key VARCHAR(80) NOT NULL,
    probe_value VARCHAR(255) NOT NULL,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_backup_restore_probe (tenant_id, probe_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
