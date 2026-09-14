package com.xianyusmart.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DASH-01~03 隔离验收夹具。只写本地商品经营指标，不调用闲鱼平台；
 * 仅 qa profile、显式开关、租户/店铺/商品前缀四重白名单同时满足时可用。
 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/business-analytics")
public class QaBusinessAnalyticsController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_FIXTURE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ProductBatchQaMockService qaMockService;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public QaBusinessAnalyticsController(JdbcTemplate jdbcTemplate,
                                         ProductBatchQaMockService qaMockService,
                                         AccountAccessService accountAccessService,
                                         OperationLogService operationLogService,
                                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.qaMockService = qaMockService;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/fixtures")
    @Transactional
    public ResultObject<Map<String, Object>> fixtures(@RequestBody FixtureRequest request) {
        requireEnabled();
        validate(request);

        Long tenantId = TenantContext.get();
        Map<String, Object> configuration = qaMockService.publicConfiguration();
        Long allowedTenantId = ((Number) configuration.get("tenantId")).longValue();
        @SuppressWarnings("unchecked")
        Set<Long> allowedAccounts = (Set<Long>) configuration.get("accountIds");
        String goodsPrefix = String.valueOf(configuration.get("goodsPrefix"));
        if (tenantId == null || !tenantId.equals(allowedTenantId)) {
            throw new BusinessException(403, "当前租户不在隔离 QA 白名单");
        }
        if (!allowedAccounts.contains(request.accountId())) {
            throw new BusinessException(403, "当前店铺不在隔离 QA 白名单");
        }
        accountAccessService.requireAccess(request.accountId());

        int size = request.size() == null ? MAX_FIXTURE_SIZE : request.size();
        List<String> goodsIds = jdbcTemplate.queryForList("""
                SELECT xy_good_id FROM xianyu_goods
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id LIKE ?
                 ORDER BY xy_good_id LIMIT ?
                """, String.class, tenantId, request.accountId(), goodsPrefix + "%", size);
        if (goodsIds.isEmpty()) {
            throw new BusinessException(409, "白名单店铺没有可用的 QA 商品，请先加载隔离商品夹具");
        }

        LocalDate metricDate = request.metricDate() == null
                ? LocalDate.now(BUSINESS_ZONE) : request.metricDate();
        for (int index = 0; index < goodsIds.size(); index++) {
            upsertMetric(tenantId, request.accountId(), goodsIds.get(index), metricDate, index, request.requestId());
        }

        boolean replayed = auditExists(tenantId, request.requestId());
        if (!replayed) logFixture(request, metricDate, goodsIds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("safeFixture", true);
        result.put("platformNetworkCalls", false);
        result.put("source", "QA_FIXTURE");
        result.put("coverageStatus", "FULL");
        result.put("accountId", request.accountId());
        result.put("metricDate", metricDate);
        result.put("upsertedRows", goodsIds.size());
        result.put("requestedRows", size);
        result.put("replayed", replayed);
        result.put("scenarios", List.of("HIGH_EXPOSURE_LOW_CLICK", "HIGH_INQUIRY_LOW_PAYMENT", "RANKING_100"));
        return ResultObject.success(result);
    }

    private void requireEnabled() {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
    }

    private void validate(FixtureRequest request) {
        if (request == null || request.requestId() == null
                || !request.requestId().matches("qa-[A-Za-z0-9._:-]{1,67}")) {
            throw new BusinessException(400, "requestId 必须以 qa- 开头，仅含字母、数字、点、横线、下划线或冒号，且不超过70个字符");
        }
        if (request.accountId() == null) throw new BusinessException(400, "accountId 不能为空");
        if (request.size() != null && (request.size() < 1 || request.size() > MAX_FIXTURE_SIZE)) {
            throw new BusinessException(400, "size 必须在1到100之间");
        }
    }

    private void upsertMetric(Long tenantId, Long accountId, String goodsId, LocalDate day,
                              int index, String requestId) {
        long exposure = index == 0 ? 1000L : 300L + index * 7L;
        long visitor = index == 0 ? 180L : 80L + index;
        long clicks = index == 0 ? 5L : index == 1 ? 60L : 20L + index % 15L;
        long favorites = 4L + index % 12L;
        long inquiries = index == 1 ? 20L : 2L + index % 8L;
        long paidOrders = index == 1 ? 0L : 1L + index % 6L;
        BigDecimal paidAmount = BigDecimal.valueOf(paidOrders).multiply(BigDecimal.valueOf(29.90 + index));
        BigDecimal conversion = visitor == 0 ? null
                : BigDecimal.valueOf(paidOrders).divide(BigDecimal.valueOf(visitor), 6, java.math.RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_metric_daily
                (tenant_id,xianyu_account_id,xy_goods_id,metric_date,exposure_count,visitor_count,
                 click_count,favorite_count,inquiry_count,paid_order_count,paid_amount,conversion_rate,
                 source,coverage_status,synced_at,request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'QA_FIXTURE','FULL',NOW(3),?)
                ON DUPLICATE KEY UPDATE exposure_count=VALUES(exposure_count),visitor_count=VALUES(visitor_count),
                 click_count=VALUES(click_count),favorite_count=VALUES(favorite_count),
                 inquiry_count=VALUES(inquiry_count),paid_order_count=VALUES(paid_order_count),
                 paid_amount=VALUES(paid_amount),conversion_rate=VALUES(conversion_rate),
                 coverage_status='FULL',synced_at=NOW(3),request_id=VALUES(request_id)
                """, tenantId, accountId, goodsId, Date.valueOf(day), exposure, visitor, clicks,
                favorites, inquiries, paidOrders, paidAmount, conversion, requestId);
    }

    private boolean auditExists(Long tenantId, String requestId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_operation_log
                 WHERE tenant_id=? AND operation_type='ANALYTICS_QA_FIXTURE' AND request_id=?
                """, Long.class, tenantId, requestId);
        return count != null && count > 0;
    }

    private void logFixture(FixtureRequest request, LocalDate metricDate, List<String> goodsIds) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(request.accountId());
        log.setOperationType("ANALYTICS_QA_FIXTURE");
        log.setOperationModule("经营分析");
        log.setOperationDesc("生成隔离经营分析夹具，不触达闲鱼平台");
        log.setOperationStatus(1);
        log.setTargetType("GOODS_METRIC_DAILY");
        log.setTargetId(request.accountId() + ":" + metricDate);
        log.setRequestParams(json(Map.of("accountId", request.accountId(), "metricDate", metricDate,
                "size", goodsIds.size(), "safeFixture", true)));
        log.setResponseResult(json(Map.of("upsertedRows", goodsIds.size(), "platformNetworkCalls", false,
                "source", "QA_FIXTURE", "coverageStatus", "FULL")));
        log.setRequestId(request.requestId());
        log.setIdempotencyKey(request.requestId());
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("QA_FIXTURE");
        operationLogService.log(log);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "QA 夹具审计序列化失败");
        }
    }

    public record FixtureRequest(String requestId, Long accountId, Integer size, LocalDate metricDate) {}
}
