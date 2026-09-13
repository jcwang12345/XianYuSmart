package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按店串行执行已确认的商品批量任务；未知平台结果不会自动重试。 */
@Slf4j
@Service
public class ProductBatchExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformPublishService platformPublishService;
    private final ItemDetailSyncService itemDetailSyncService;
    private final GoodsAutomationService goodsAutomationService;
    private final ObjectMapper objectMapper;

    public ProductBatchExecutionService(JdbcTemplate jdbcTemplate,
                                        PlatformPublishService platformPublishService,
                                        ItemDetailSyncService itemDetailSyncService,
                                        GoodsAutomationService goodsAutomationService,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.platformPublishService = platformPublishService;
        this.itemDetailSyncService = itemDetailSyncService;
        this.goodsAutomationService = goodsAutomationService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.product-batch.dispatch-delay-ms:5000}", initialDelay = 30000)
    public void dispatch() {
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                SELECT * FROM xianyu_goods_batch_job
                 WHERE status IN ('QUEUED','RUNNING') ORDER BY created_time, id LIMIT 10
                """);
        for (Map<String, Object> job : jobs) {
            executeJob(job);
        }
    }

    void executeJob(Map<String, Object> job) {
        Long tenantId = number(job.get("tenant_id"));
        Long jobId = number(job.get("id"));
        if (tenantId == null || jobId == null) return;
        TenantContext.set(tenantId);
        try {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_job SET status='RUNNING', started_time=COALESCE(started_time,NOW(3))
                     WHERE tenant_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                    """, tenantId, jobId);
            List<Map<String, Object>> due = jdbcTemplate.queryForList("""
                    SELECT * FROM xianyu_goods_batch_item
                     WHERE tenant_id=? AND batch_job_id=?
                       AND (status='QUEUED' OR (status='FAILED' AND attempt_count < max_attempts
                            AND (next_retry_time IS NULL OR next_retry_time<=NOW(3))))
                     ORDER BY xianyu_account_id, id
                    """, tenantId, jobId);
            // 单次调度每个店只领取一个，天然按店串行并为平台限流留出间隔。
            Map<Long, Map<String, Object>> onePerAccount = new LinkedHashMap<>();
            for (Map<String, Object> item : due) {
                Long accountId = number(item.get("xianyu_account_id"));
                if (accountId != null) onePerAccount.putIfAbsent(accountId, item);
            }
            for (Map<String, Object> item : onePerAccount.values()) executeItem(job, item);
            refreshJob(jobId, tenantId);
        } catch (Exception e) {
            log.warn("商品批量任务调度失败: tenantId={}, jobId={}, error={}", tenantId, jobId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private void executeItem(Map<String, Object> job, Map<String, Object> item) {
        Long tenantId = number(job.get("tenant_id"));
        Long jobId = number(job.get("id"));
        Long itemId = number(item.get("id"));
        Long accountId = number(item.get("xianyu_account_id"));
        String goodsId = text(item.get("xy_goods_id"));
        String operation = text(item.get("operation_type")).toUpperCase(Locale.ROOT);
        int claimed = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item
                   SET status='RUNNING', started_time=NOW(3), attempt_count=attempt_count+1
                 WHERE tenant_id=? AND id=? AND status IN ('QUEUED','FAILED')
                """, tenantId, itemId);
        if (claimed != 1) return;
        try {
            Map<String, Object> result = switch (operation) {
                case "SYNC" -> sync(accountId, goodsId);
                case "ON_SALE" -> platformPublishService.changeListingStatus(accountId, goodsId, true);
                case "OFF_SHELF" -> platformPublishService.changeListingStatus(accountId, goodsId, false);
                case "DELETE" -> platformPublishService.delete(accountId, goodsId);
                case "POLISH" -> Map.of("success", goodsAutomationService.polishOne(accountId, goodsId));
                default -> throw new IllegalStateException("当前接入通道不支持" + operation);
            };
            if (!Boolean.TRUE.equals(result.get("success"))) throw new IllegalStateException("平台操作未确认成功");
            boolean localUpdated = updateLocalState(tenantId, accountId, goodsId, operation);
            String outcome = localUpdated || "SYNC".equals(operation)
                    ? "PLATFORM_CONFIRMED" : "PLATFORM_CONFIRMED_LOCAL_PENDING";
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status='SUCCEEDED', outcome_state=?, result_json=?, error_message=?, completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, outcome, json(result), localUpdated || "SYNC".equals(operation) ? null : "平台已成功，本地状态待修复",
                    tenantId, itemId);
            recordEvent(job, item, outcome, result, null);
        } catch (Exception e) {
            String message = limit(e.getMessage());
            int attempts = integer(item.get("attempt_count"), 0) + 1;
            boolean unknown = isUnknownResult(message);
            boolean exhausted = attempts >= integer(item.get("max_attempts"), 3);
            String status = unknown ? "UNKNOWN" : "FAILED";
            LocalDateTime retryAt = unknown || exhausted ? null : LocalDateTime.now().plusSeconds(Math.min(300, attempts * 30L));
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status=?, outcome_state=?, error_message=?, next_retry_time=?, completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, status, unknown ? "UNKNOWN" : "FAILED", message, retryAt, tenantId, itemId);
            recordEvent(job, item, unknown ? "UNKNOWN" : "FAILED", null, message);
        }
    }

    private Map<String, Object> sync(Long accountId, String goodsId) {
        boolean success = itemDetailSyncService.syncSingleItem(accountId, goodsId);
        return Map.of("success", success, "itemId", goodsId);
    }

    private boolean updateLocalState(Long tenantId, Long accountId, String goodsId, String operation) {
        Integer status = switch (operation) {
            case "ON_SALE" -> 0;
            case "OFF_SHELF" -> 1;
            case "DELETE" -> -1;
            default -> null;
        };
        if (status == null) return true;
        return jdbcTemplate.update("""
                UPDATE xianyu_goods SET status=?, sync_status='SUCCEEDED', coverage_status='PARTIAL',
                       last_synced_time=NOW(3), last_sync_error_code=NULL, last_sync_error_message=NULL
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, status, tenantId, accountId, goodsId) == 1;
    }

    private void recordEvent(Map<String, Object> job, Map<String, Object> item, String outcome,
                             Map<String, Object> result, String error) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_event
                (tenant_id, xianyu_account_id, xy_goods_id, event_type, event_origin, outcome_state,
                 data_source, operator_user_id, operator_username, request_id, idempotency_key,
                 after_json, error_message)
                VALUES (?,?,?,?, 'BATCH_TASK',?,'PLATFORM_WEB',?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE outcome_state=VALUES(outcome_state), after_json=VALUES(after_json),
                 error_message=VALUES(error_message)
                """, number(job.get("tenant_id")), number(item.get("xianyu_account_id")), text(item.get("xy_goods_id")),
                "BATCH_" + text(item.get("operation_type")), outcome, number(job.get("operator_user_id")),
                job.get("operator_username"), job.get("request_id"), job.get("request_id"), json(result), error);
    }

    private void refreshJob(Long jobId, Long tenantId) {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) total,
                       SUM(status='SUCCEEDED') succeeded,
                       SUM(status='FAILED') failed,
                       SUM(status='UNKNOWN') unknown_count,
                       SUM(status IN ('QUEUED','RUNNING')) active,
                       SUM(status='CONFLICT') conflicts
                  FROM xianyu_goods_batch_item WHERE tenant_id=? AND batch_job_id=?
                """, tenantId, jobId);
        long active = longValue(counts.get("active"));
        long success = longValue(counts.get("succeeded"));
        long failed = longValue(counts.get("failed"));
        long unknown = longValue(counts.get("unknown_count"));
        String status;
        if (active > 0) status = "RUNNING";
        else if (failed == 0 && unknown == 0) status = "SUCCEEDED";
        else if (success == 0) status = "FAILED";
        else status = "PARTIAL";
        jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_job
                   SET status=?, success_count=?, failed_count=?, unknown_count=?,
                       completed_time=IF(?='RUNNING',NULL,NOW(3))
                 WHERE tenant_id=? AND id=?
                """, status, success, failed, unknown, status, tenantId, jobId);
    }

    private boolean isUnknownResult(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")
                || lower.contains("结果无法确认") || lower.contains("缺少商品id");
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"serializationError\":true}"; }
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String limit(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
