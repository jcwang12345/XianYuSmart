package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** 处罚同步尝试、数据集终态与审计的短事务边界。 */
@Service
public class ShopRiskSyncPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public ShopRiskSyncPersistenceService(JdbcTemplate jdbcTemplate,
                                          OperationLogService operationLogService,
                                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean createAttempt(Long accountId, String requestId, String triggerType) {
        jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE
                """, Long.class, tenant(), accountId);
        Integer running = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_shop_risk_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND status='RUNNING'
                """, Integer.class, tenant(), accountId);
        if (running != null && running > 0) return false;
        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_risk_sync_attempt
                (tenant_id,xianyu_account_id,request_id,trigger_type,status,source,coverage_status,started_at)
                VALUES (?,?,?,?,'RUNNING','NONE','UNSYNCED',NOW(3))
                """, tenant(), accountId, requestId, triggerType);
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id,xianyu_account_id,dataset_code,source,sync_status,coverage_status,
                 last_attempt_time,started_at,request_id)
                VALUES (?,?,'SHOP_RISKS','NONE','RUNNING','UNSYNCED',NOW(3),NOW(3),?)
                ON DUPLICATE KEY UPDATE source='NONE',sync_status='RUNNING',last_attempt_time=NOW(3),
                 started_at=NOW(3),last_error_code=NULL,last_error_message=NULL,retry_after=NULL,
                recovery_hint=NULL,request_id=VALUES(request_id)
                """, tenant(), accountId, requestId);
        return true;
    }

    @Transactional
    public void completeSuccess(Long accountId, String requestId, String coverage,
                                int recordCount, int pageCount, Instant now) {
        jdbcTemplate.update("""
                UPDATE xianyu_shop_risk_sync_attempt
                   SET status='SUCCEEDED',source='PLATFORM_API',coverage_status=?,record_count=?,page_count=?,
                       completed_at=NOW(3),error_code=NULL,error_message=NULL,recovery_hint=NULL
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=? AND status='RUNNING'
                """, coverage, recordCount, pageCount, tenant(), accountId, requestId);
        jdbcTemplate.update("""
                UPDATE xianyu_account_dataset_state
                   SET source='PLATFORM_API',sync_status='SUCCEEDED',coverage_status=?,as_of_time=?,
                       last_attempt_time=NOW(3),last_success_time=NOW(3),last_error_code=NULL,
                       last_error_message=NULL,retry_after=NULL,recovery_hint=NULL
                 WHERE tenant_id=? AND xianyu_account_id=? AND dataset_code='SHOP_RISKS' AND request_id=?
                """, coverage, Timestamp.from(now), tenant(), accountId, requestId);
        audit(accountId, requestId, 1, "PLATFORM_CONFIRMED", null, null,
                Map.of("coverageStatus", coverage, "recordCount", recordCount, "pageCount", pageCount));
    }

    @Transactional
    public void completeFailure(Long accountId, String requestId, String code, String message, String recoveryHint) {
        jdbcTemplate.update("""
                UPDATE xianyu_shop_risk_sync_attempt
                   SET status='FAILED',source='PLATFORM_API',coverage_status='UNSYNCED',completed_at=NOW(3),
                       error_code=?,error_message=?,recovery_hint=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=? AND status='RUNNING'
                """, code, safe(message), safe(recoveryHint), tenant(), accountId, requestId);
        jdbcTemplate.update("""
                UPDATE xianyu_account_dataset_state
                   SET source='PLATFORM_API',sync_status='FAILED',last_attempt_time=NOW(3),
                       last_error_code=?,last_error_message=?,recovery_hint=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND dataset_code='SHOP_RISKS' AND request_id=?
                """, code, safe(message), safe(recoveryHint), tenant(), accountId, requestId);
        audit(accountId, requestId, 0, "PLATFORM_FAILED", code, message, null);
    }

    private void audit(Long accountId, String requestId, int status, String outcome,
                       String errorCode, String errorMessage, Object result) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType("RISK_SNAPSHOT_SYNC");
        log.setOperationModule("账号风险");
        log.setOperationDesc(status == 1 ? "同步平台处罚快照" : "平台处罚快照同步失败");
        log.setOperationStatus(status);
        log.setTargetType("SHOP_RISKS");
        log.setTargetId(String.valueOf(accountId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("PLATFORM_API");
        log.setResponseResult(errorCode == null ? json(result) : errorCode);
        log.setErrorMessage(safe(errorMessage));
        operationLogService.logRequired(log);
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "处罚同步审计序列化失败", e);
        }
    }

    private String safe(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }
}
