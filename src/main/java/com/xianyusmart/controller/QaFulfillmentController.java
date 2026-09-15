package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V6-FUL 隔离验收夹具。只构造本地 QA 库存和事件，不调用闲鱼或外部供货接口。
 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/fulfillment")
public class QaFulfillmentController {

    private static final String ALIAS_PREFIX = "QA-FULFILLMENT-STATES-";

    private final JdbcTemplate jdbcTemplate;
    private final ProductBatchQaMockService qaMockService;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;

    public QaFulfillmentController(JdbcTemplate jdbcTemplate,
                                   ProductBatchQaMockService qaMockService,
                                   AccountAccessService accountAccessService,
                                   OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.qaMockService = qaMockService;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
    }

    @PostMapping("/fixtures")
    @Transactional
    public ResultObject<Map<String, Object>> fixture(@RequestBody FixtureRequest request) {
        requireSafeRequest(request);
        Long tenantId = TenantContext.get();
        accountAccessService.requireAccess(request.accountId());

        String alias = ALIAS_PREFIX + request.accountId();
        List<Long> existing = jdbcTemplate.queryForList("""
                SELECT id FROM xianyu_kami_config
                 WHERE tenant_id=? AND xianyu_account_id=? AND alias_name=?
                 ORDER BY id ASC LIMIT 1
                """, Long.class, tenantId, request.accountId(), alias);
        Long configId;
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_kami_config
                    (tenant_id,xianyu_account_id,sharing_mode,config_version,alias_name,source_type,
                     external_api_url,external_api_body,external_api_result_path,external_api_timeout_seconds,
                     external_daily_quota,external_failure_threshold,external_cooldown_seconds,
                     external_circuit_state,external_consecutive_failures,external_circuit_opened_at,
                     external_quota_date,external_quota_used,alert_enabled,alert_threshold_type,
                     alert_threshold_value,total_count,used_count)
                    VALUES (?,?,'PRIVATE',1,?,'API','https://qa.invalid/no-network','{}','$.data',3,
                            50,3,300,?,3,NOW(3),CURRENT_DATE,7,1,1,2,4,1)
                    """, tenantId, request.accountId(), alias, circuitState(request.circuitState()));
            configId = jdbcTemplate.queryForObject("""
                    SELECT id FROM xianyu_kami_config
                     WHERE tenant_id=? AND xianyu_account_id=? AND alias_name=?
                     ORDER BY id ASC LIMIT 1
                    """, Long.class, tenantId, request.accountId(), alias);
        } else {
            configId = existing.getFirst();
            jdbcTemplate.update("""
                    UPDATE xianyu_kami_config
                       SET config_version=config_version+1,source_type='API',
                           external_api_url='https://qa.invalid/no-network',external_api_body='{}',
                           external_api_result_path='$.data',external_api_timeout_seconds=3,
                           external_daily_quota=50,external_failure_threshold=3,external_cooldown_seconds=300,
                           external_circuit_state=?,external_consecutive_failures=3,
                           external_circuit_opened_at=NOW(3),external_quota_date=CURRENT_DATE,
                           external_quota_used=7,total_count=4,used_count=1
                     WHERE tenant_id=? AND id=?
                    """, circuitState(request.circuitState()), tenantId, configId);
        }

        jdbcTemplate.update("INSERT IGNORE INTO xianyu_kami_config_account "
                + "(kami_config_id,tenant_id,xianyu_account_id) VALUES (?,?,?)",
                configId, tenantId, request.accountId());
        // 删除范围被 alias + tenant + account 三重白名单锁定，只重建本夹具自己的明细。
        jdbcTemplate.update("DELETE FROM xianyu_kami_inventory_event WHERE tenant_id=? AND kami_config_id=?",
                tenantId, configId);
        jdbcTemplate.update("DELETE FROM xianyu_kami_external_request WHERE tenant_id=? AND kami_config_id=?",
                tenantId, configId);
        jdbcTemplate.update("DELETE FROM xianyu_kami_item WHERE tenant_id=? AND kami_config_id=?",
                tenantId, configId);
        long configVersion = jdbcTemplate.queryForObject(
                "SELECT config_version FROM xianyu_kami_config WHERE tenant_id=? AND id=?",
                Long.class, tenantId, configId);

        insertItem(tenantId, configId, request.accountId(), request.requestId(), configVersion,
                "AVAILABLE", 0, null, 0);
        insertItem(tenantId, configId, request.accountId(), request.requestId(), configVersion,
                "DELIVERED", 1, "QA-ORDER-FUL-DONE-" + request.accountId(), 1);
        insertItem(tenantId, configId, request.accountId(), request.requestId(), configVersion,
                "RESERVED", 2, "QA-ORDER-FUL-RESERVED-" + request.accountId(), 2);
        insertItem(tenantId, configId, request.accountId(), request.requestId(), configVersion,
                "REVIEW", 3, "QA-ORDER-FUL-REVIEW-" + request.accountId(), 3);

        String unknownOrderId = "QA-ORDER-FUL-SUPPLY-UNKNOWN-" + request.accountId();
        jdbcTemplate.update("""
                INSERT INTO xianyu_kami_external_request
                (tenant_id,kami_config_id,xianyu_account_id,order_id,request_token,payload_fingerprint,
                 quantity,request_status,result_unknown,attempt_count,error_message,next_retry_time,
                 circuit_state_at_request,quota_used_after,create_time,update_time)
                VALUES (?,?,?,?,?,REPEAT('a',64),2,'REVIEW_REQUIRED',1,1,
                        'QA fixture: supplier response timed out after request',NULL,?,7,NOW(3),NOW(3))
                """, tenantId, configId, request.accountId(), unknownOrderId,
                "qa-secret-token-" + request.requestId(), circuitState(request.circuitState()));
        Long externalRequestId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_kami_external_request
                 WHERE tenant_id=? AND kami_config_id=? AND order_id=?
                """, Long.class, tenantId, configId, unknownOrderId);

        int eventCount = request.eventCount() == null ? 25 : request.eventCount();
        Long firstItemId = jdbcTemplate.queryForObject("""
                SELECT MIN(id) FROM xianyu_kami_item WHERE tenant_id=? AND kami_config_id=?
                """, Long.class, tenantId, configId);
        String[] eventTypes = {"IMPORTED", "RESERVED", "CONSUMED", "RELEASED", "REVIEW_REQUIRED", "CIRCUIT_OPENED"};
        for (int i = 0; i < eventCount; i++) {
            String type = eventTypes[i % eventTypes.length];
            jdbcTemplate.update("""
                    INSERT INTO xianyu_kami_inventory_event
                    (tenant_id,kami_config_id,kami_item_id,xianyu_account_id,order_id,event_type,event_key,
                     outcome_state,request_id,config_version,quantity,before_json,after_json,source)
                    VALUES (?,?,?,?,?,?,?,?,?,?,1,'{"status":"BEFORE"}','{"status":"AFTER"}','QA_FIXTURE')
                    """, tenantId, configId, firstItemId, request.accountId(),
                    "QA-ORDER-FUL-EVENT-" + i, type,
                    "QA_FIXTURE:" + configId + ":" + request.requestId() + ":" + i,
                    i % 7 == 0 ? "UNKNOWN" : "LOCAL_SUCCESS",
                    request.requestId() + "-" + i, configVersion);
        }

        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(request.accountId());
        audit.setOperationType("QA_FULFILLMENT_FIXTURE");
        audit.setOperationModule("卡密库存");
        audit.setOperationDesc("创建隔离履约验收夹具");
        audit.setOperationStatus(1);
        audit.setTargetType("KAMI_CONFIG");
        audit.setTargetId(String.valueOf(configId));
        audit.setRequestId(request.requestId());
        audit.setIdempotencyKey(request.requestId());
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("QA_FIXTURE");
        audit.setRequestParams("{\"eventCount\":" + eventCount + ",\"platformNetworkCalls\":false}");
        audit.setResponseResult("{\"configId\":" + configId + ",\"itemCount\":4}");
        operationLogService.logRequired(audit);

        return ResultObject.success(Map.of(
                "safeFixture", true,
                "platformNetworkCalls", false,
                "tenantId", tenantId,
                "accountId", request.accountId(),
                "configId", configId,
                "configVersion", configVersion,
                "externalRequestId", externalRequestId,
                "externalUnknownOrderId", unknownOrderId,
                "itemStates", List.of("AVAILABLE", "DELIVERED", "RESERVED", "REVIEW_REQUIRED"),
                "eventCount", eventCount));
    }

    private void insertItem(Long tenantId, Long configId, Long accountId, String requestId,
                            long configVersion, String label, int status, String orderId, int sortOrder) {
        boolean unsettled = status == 2 || status == 3;
        jdbcTemplate.update("""
                INSERT INTO xianyu_kami_item
                (tenant_id,kami_config_id,kami_content,status,order_id,reserved_account_id,
                 reservation_token,reservation_expire_time,source_config_version,row_version,
                 reserved_time,used_time,sort_order)
                VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?)
                """, tenantId, configId, "QA-CARD-" + label + "-" + requestId,
                status, orderId, unsettled ? accountId : null,
                unsettled ? "qa-reservation-" + requestId + "-" + sortOrder : null,
                unsettled ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusMinutes(30)) : null,
                unsettled ? configVersion : null,
                unsettled ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null,
                status == 1 ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null,
                sortOrder);
    }

    private void requireSafeRequest(FixtureRequest request) {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
        if (request == null || request.accountId() == null || request.requestId() == null
                || !request.requestId().startsWith("qa-") || request.requestId().length() > 60) {
            throw new BusinessException(400, "仅接受 qa- 开头且不超过60字符的 requestId");
        }
        int eventCount = request.eventCount() == null ? 25 : request.eventCount();
        if (eventCount < 0 || eventCount > 1000) throw new BusinessException(400, "eventCount 应为0至1000");
        Long tenantId = TenantContext.get();
        Long allowedTenant = ((Number) qaMockService.publicConfiguration().get("tenantId")).longValue();
        @SuppressWarnings("unchecked")
        Set<Long> allowedAccounts = (Set<Long>) qaMockService.publicConfiguration().get("accountIds");
        if (tenantId == null || !tenantId.equals(allowedTenant) || !allowedAccounts.contains(request.accountId())) {
            throw new BusinessException(403, "当前租户或账号不在隔离 QA 白名单");
        }
    }

    private String circuitState(String requested) {
        return "CLOSED".equalsIgnoreCase(requested) ? "CLOSED" : "OPEN";
    }

    public record FixtureRequest(Long accountId, String requestId, Integer eventCount, String circuitState) {}
}
