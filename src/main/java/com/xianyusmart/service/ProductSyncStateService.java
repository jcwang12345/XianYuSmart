package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 将商品同步尝试、覆盖范围和错误与商品缓存分开记录。 */
@Service
public class ProductSyncStateService {

    private final JdbcTemplate jdbcTemplate;

    public ProductSyncStateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void completed(Long accountId, String requestId, boolean fullCoverage) {
        upsert(accountId, requestId, "SUCCEEDED", fullCoverage ? "FULL" : "PARTIAL", null, null);
        jdbcTemplate.update("""
                UPDATE xianyu_goods
                   SET sync_status='SUCCEEDED', coverage_status=?, last_synced_time=NOW(3),
                       last_sync_request_id=?, last_sync_error_code=NULL, last_sync_error_message=NULL
                 WHERE tenant_id=? AND xianyu_account_id=?
                """, fullCoverage ? "FULL" : "PARTIAL", requestId, tenant(), accountId);
    }

    public void failed(Long accountId, String requestId, String errorCode, String errorMessage) {
        upsert(accountId, requestId, "FAILED", "UNSYNCED", errorCode, limit(errorMessage));
    }

    private void upsert(Long accountId, String requestId, String syncStatus, String coverage,
                        String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id, xianyu_account_id, dataset_code, source, sync_status, coverage_status,
                 as_of_time, last_attempt_time, last_success_time, last_error_code, last_error_message, request_id)
                VALUES (?,?,'PRODUCTS','PLATFORM_WEB',?,?,NOW(3),NOW(3),?, ?,?,?)
                ON DUPLICATE KEY UPDATE source=VALUES(source), sync_status=VALUES(sync_status),
                 coverage_status=VALUES(coverage_status), as_of_time=VALUES(as_of_time),
                 last_attempt_time=VALUES(last_attempt_time),
                 last_success_time=COALESCE(VALUES(last_success_time), last_success_time),
                 last_error_code=VALUES(last_error_code), last_error_message=VALUES(last_error_message),
                 request_id=VALUES(request_id)
                """, tenant(), accountId, syncStatus, coverage,
                "FAILED".equals(syncStatus) ? null : new java.sql.Timestamp(System.currentTimeMillis()),
                errorCode, errorMessage, requestId);
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
