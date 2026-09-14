package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

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
    private final NotificationCenterService notificationCenterService;
    private final ProductBatchQaMockService qaMockService;
    private final boolean dispatchEnabled;
    private final String workerId = "product-batch-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    public ProductBatchExecutionService(JdbcTemplate jdbcTemplate,
                                        PlatformPublishService platformPublishService,
                                        ItemDetailSyncService itemDetailSyncService,
                                        GoodsAutomationService goodsAutomationService,
                                        ObjectMapper objectMapper,
                                        NotificationCenterService notificationCenterService,
                                        ProductBatchQaMockService qaMockService,
                                        @Value("${app.product-batch.dispatch-enabled:true}") boolean dispatchEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.platformPublishService = platformPublishService;
        this.itemDetailSyncService = itemDetailSyncService;
        this.goodsAutomationService = goodsAutomationService;
        this.objectMapper = objectMapper;
        this.notificationCenterService = notificationCenterService;
        this.qaMockService = qaMockService;
        this.dispatchEnabled = dispatchEnabled;
    }

    @PostConstruct
    public void recoverInterruptedWork() {
        int unknown = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item
                   SET status='UNKNOWN',outcome_state='UNKNOWN',error_code='WORKER_RESTART',
                       error_message='服务重启时子项正在执行，平台结果需人工核对',completed_time=NOW(3)
                 WHERE status='RUNNING'
                """);
        int recovered = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_job
                   SET status='QUEUED',recovery_count=recovery_count+1,last_dispatch_time=NULL
                 WHERE status='RUNNING' AND EXISTS (
                    SELECT 1 FROM xianyu_goods_batch_item item
                     WHERE item.batch_job_id=xianyu_goods_batch_job.id AND item.status='QUEUED')
                """);
        if (unknown + recovered > 0) log.warn("已恢复商品任务: 未知子项={}, 重新排队任务={}", unknown, recovered);
    }

    @Scheduled(fixedDelayString = "${app.product-batch.dispatch-delay-ms:5000}", initialDelay = 30000)
    public void dispatch() {
        if (!dispatchEnabled) return;
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                SELECT * FROM xianyu_goods_batch_job
                 WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED') ORDER BY created_time, id LIMIT 10
                """);
        for (Map<String, Object> job : jobs) {
            executeJob(job);
        }
    }

    /** QA 控制器使用的单步调度；只接受已经持久化为 QA_MOCK 的任务。 */
    public Map<String, Object> dispatchOneQaJob(Long tenantId, Long jobId) {
        Map<String, Object> job = requireQaJob(tenantId, jobId);
        executeJob(job);
        return qaJobState(tenantId, jobId);
    }

    /** 可控排空便于验证 100/1000 子项；遇到限速无进展会停止，不伪造进度。 */
    public Map<String, Object> drainQaJob(Long tenantId, Long jobId, int requestedCycles) {
        int maxCycles = Math.max(1, Math.min(requestedCycles, 2000));
        int cycles = 0;
        int unchanged = 0;
        long previousCompleted = -1;
        while (cycles++ < maxCycles) {
            Map<String, Object> job = requireQaJob(tenantId, jobId);
            if (!List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(text(job.get("status")))) break;
            executeJob(job);
            Map<String, Object> state = qaJobState(tenantId, jobId);
            long completed = longValue(state.get("completed"));
            unchanged = completed == previousCompleted ? unchanged + 1 : 0;
            previousCompleted = completed;
            if (unchanged >= 2) break;
        }
        Map<String, Object> result = qaJobState(tenantId, jobId);
        result.put("cycles", cycles - 1);
        result.put("stoppedForRateLimit", longValue(result.get("active")) > 0 && unchanged >= 2);
        return result;
    }

    public Map<String, Object> prepareQaRestartFault(Long tenantId, Long jobId) {
        requireQaJob(tenantId, jobId);
        int changed = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item SET status='RUNNING',outcome_state='RUNNING',
                       claimed_by='QA-RESTART-FAULT',claimed_time=NOW(3),started_time=NOW(3)
                 WHERE tenant_id=? AND batch_job_id=? AND status='QUEUED' ORDER BY id LIMIT 1
                """, tenantId, jobId);
        if (changed != 1) throw new BusinessException(409, "任务没有可用于重启恢复验证的排队子项");
        jdbcTemplate.update("UPDATE xianyu_goods_batch_job SET status='RUNNING' WHERE tenant_id=? AND id=?", tenantId, jobId);
        return qaJobState(tenantId, jobId);
    }

    public Map<String, Object> recoverQaJob(Long tenantId, Long jobId) {
        requireQaJob(tenantId, jobId);
        int unknown = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item SET status='UNKNOWN',outcome_state='UNKNOWN',error_code='WORKER_RESTART',
                       error_message='隔离 QA 重启恢复：执行中子项结果未知',completed_time=NOW(3)
                 WHERE tenant_id=? AND batch_job_id=? AND status='RUNNING'
                """, tenantId, jobId);
        int requeued = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_job SET status='QUEUED',recovery_count=recovery_count+1,last_dispatch_time=NULL
                 WHERE tenant_id=? AND id=? AND execution_channel='QA_MOCK'
                   AND EXISTS (SELECT 1 FROM xianyu_goods_batch_item item WHERE item.batch_job_id=? AND item.status='QUEUED')
                """, tenantId, jobId, jobId);
        Map<String, Object> result = qaJobState(tenantId, jobId);
        result.put("recoveredUnknown", unknown);
        result.put("requeuedJobs", requeued);
        return result;
    }

    void executeJob(Map<String, Object> job) {
        Long tenantId = number(job.get("tenant_id"));
        Long jobId = number(job.get("id"));
        if (tenantId == null || jobId == null) return;
        TenantContext.set(tenantId);
        try {
            if ("CANCEL_REQUESTED".equals(text(job.get("status")))) {
                cancelQueuedItems(tenantId, jobId);
                refreshJob(jobId, tenantId);
                return;
            }
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_job SET status='RUNNING', started_time=COALESCE(started_time,NOW(3)),last_dispatch_time=NOW(3)
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
        boolean qaMock = qaMockService.isEligible(tenantId, accountId, goodsId);
        if ((qaMock && qaMockService.simulateAuthorizationRevoked(job)) || !authorizationStillValid(job, accountId)) {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item SET status='SKIPPED',outcome_state='AUTHORIZATION_REVOKED',
                           error_code='AUTHORIZATION_REVOKED',error_message='任务创建人的商品操作权限或店铺范围已被撤销',completed_time=NOW(3)
                     WHERE tenant_id=? AND id=? AND status='QUEUED'
                    """, tenantId, itemId);
            recordEvent(job, item, "AUTHORIZATION_REVOKED", null, "执行前实时权限复核未通过");
            return;
        }
        if ((qaMock && qaMockService.simulateStaleVersion(job))
                || !versionMatches(tenantId, accountId, goodsId, item.get("expected_goods_version"))) {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item SET status='SKIPPED',outcome_state='PRECHECK_CONFLICT',
                           error_code='STALE_PRODUCT_VERSION',error_message='商品已在预检后发生变化，请重新预检',completed_time=NOW(3)
                     WHERE tenant_id=? AND id=? AND status='QUEUED'
                    """, tenantId, itemId);
            recordEvent(job, item, "PRECHECK_CONFLICT", null, "商品版本已变化");
            return;
        }
        int rate = integer(job.get("max_operations_per_minute"), 10);
        if (!(qaMock && qaMockService.bypassRateLimit(job)) && !acquireRateSlot(tenantId, accountId, rate)) return;
        String platformRequestId = (qaMock ? "QA-MOCK-" : "PR-") + java.util.UUID.randomUUID();
        int claimed = jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item
                   SET status='RUNNING', started_time=NOW(3), attempt_count=attempt_count+1,
                       platform_request_id=?,claimed_by=?,claimed_time=NOW(3)
                 WHERE tenant_id=? AND id=? AND status IN ('QUEUED','FAILED')
                """, platformRequestId, workerId, tenantId, itemId);
        if (claimed != 1) return;
        item.put("platform_request_id", platformRequestId);
        try {
            Map<String, Object> result = qaMock ? qaMockService.execute(job, item) : switch (operation) {
                case "SYNC" -> sync(accountId, goodsId);
                case "ON_SALE" -> platformPublishService.changeListingStatus(accountId, goodsId, true);
                case "OFF_SHELF" -> platformPublishService.changeListingStatus(accountId, goodsId, false);
                case "DELETE" -> platformPublishService.delete(accountId, goodsId);
                case "POLISH" -> Map.of("success", goodsAutomationService.polishOne(accountId, goodsId));
                default -> throw new IllegalStateException("当前接入通道不支持" + operation);
            };
            if (!Boolean.TRUE.equals(result.get("success"))) throw new IllegalStateException("平台操作未确认成功");
            boolean localUpdated = qaMock || updateLocalState(tenantId, accountId, goodsId, operation);
            String outcome = qaMock ? "QA_MOCK_CONFIRMED" : localUpdated || "SYNC".equals(operation)
                    ? "PLATFORM_CONFIRMED" : "PLATFORM_CONFIRMED_LOCAL_PENDING";
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status='SUCCEEDED', outcome_state=?, result_json=?, error_message=?, completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, outcome, json(result), qaMock || localUpdated || "SYNC".equals(operation) ? null : "平台已成功，本地状态待修复",
                    tenantId, itemId);
            updateRateOutcome(tenantId, accountId, true, rate);
            recordEvent(job, item, outcome, result, null);
        } catch (Exception e) {
            String message = limit(e.getMessage());
            int attempts = integer(item.get("attempt_count"), 0) + 1;
            boolean manualRetry = e instanceof ProductBatchQaMockService.ManualRetryRequiredException;
            boolean unknown = isUnknownResult(message);
            boolean exhausted = attempts >= integer(item.get("max_attempts"), 3);
            String status = unknown ? "UNKNOWN" : "FAILED";
            LocalDateTime retryAt = unknown || exhausted ? null : manualRetry
                    ? LocalDateTime.now().plusYears(1) : LocalDateTime.now().plusSeconds(Math.min(300, attempts * 30L));
            String errorCode = unknown ? (qaMock ? "QA_MOCK_UNKNOWN" : "PLATFORM_RESULT_UNKNOWN")
                    : manualRetry ? "QA_MOCK_RETRY_REQUIRED" : "EXECUTION_FAILED";
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status=?, outcome_state=?, error_code=?, error_message=?, next_retry_time=?, completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, status, unknown ? "UNKNOWN" : "FAILED", errorCode, message, retryAt, tenantId, itemId);
            updateRateOutcome(tenantId, accountId, false, rate);
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
                UPDATE xianyu_goods SET status=?, row_version=row_version+1, sync_status='SUCCEEDED', coverage_status='PARTIAL',
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
                 batch_job_id,batch_item_id,platform_request_id,
                 after_json, error_message)
                VALUES (?,?,?,?, 'BATCH_TASK',?,?, ?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE outcome_state=VALUES(outcome_state), after_json=VALUES(after_json),
                 error_message=VALUES(error_message)
                """, number(job.get("tenant_id")), number(item.get("xianyu_account_id")), text(item.get("xy_goods_id")),
                "BATCH_" + text(item.get("operation_type")), outcome,
                qaMockService.isEligible(number(job.get("tenant_id")), number(item.get("xianyu_account_id")), text(item.get("xy_goods_id"))) ? "QA_MOCK" : "PLATFORM_WEB",
                number(job.get("operator_user_id")),
                job.get("operator_username"), job.get("request_id"), job.get("idempotency_key"), number(job.get("id")),
                number(item.get("id")), item.get("platform_request_id"), json(result), error);
    }

    private void refreshJob(Long jobId, Long tenantId) {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) total,
                       SUM(status='SUCCEEDED') succeeded,
                       SUM(status='FAILED') failed,
                       SUM(status='UNKNOWN') unknown_count,
                       SUM(status IN ('QUEUED','RUNNING')) active,
                       SUM(status='SKIPPED') skipped,
                       SUM(status='CANCELLED') cancelled,
                       SUM(status='CONFLICT') conflicts
                  FROM xianyu_goods_batch_item WHERE tenant_id=? AND batch_job_id=?
                """, tenantId, jobId);
        long active = longValue(counts.get("active"));
        long success = longValue(counts.get("succeeded"));
        long failed = longValue(counts.get("failed"));
        long unknown = longValue(counts.get("unknown_count"));
        long skipped = longValue(counts.get("skipped"));
        long cancelled = longValue(counts.get("cancelled"));
        long total = longValue(counts.get("total"));
        String current = jdbcTemplate.queryForObject("SELECT status FROM xianyu_goods_batch_job WHERE tenant_id=? AND id=?",
                String.class, tenantId, jobId);
        String status;
        if ("CANCEL_REQUESTED".equals(current) && active == 0) status = "CANCELLED";
        else if (active > 0) status = "RUNNING";
        else if (failed == 0 && unknown == 0 && skipped == 0 && cancelled == 0) status = "SUCCEEDED";
        else if (success == 0) status = "FAILED";
        else status = "PARTIAL_SUCCESS";
        java.math.BigDecimal progress = total == 0 ? java.math.BigDecimal.ZERO
                : java.math.BigDecimal.valueOf((success + failed + unknown + skipped + cancelled) * 100d / total)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_job
                   SET status=?, success_count=?, failed_count=?, unknown_count=?,skipped_count=?,cancelled_count=?,progress_percent=?,
                       completed_time=IF(?='RUNNING',NULL,NOW(3))
                 WHERE tenant_id=? AND id=?
                """, status, success, failed, unknown, skipped, cancelled, progress, status, tenantId, jobId);
        if (!"RUNNING".equals(status)) notifyCompletion(jobId, tenantId, status, success, failed, unknown, skipped, cancelled);
    }

    private boolean versionMatches(Long tenantId, Long accountId, String goodsId, Object expected) {
        if (!(expected instanceof Number number)) return true;
        Long current = jdbcTemplate.queryForObject("SELECT row_version FROM xianyu_goods WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?",
                Long.class, tenantId, accountId, goodsId);
        return current != null && current.longValue() == number.longValue();
    }

    /**
     * 任务采用“创建时校验 + 每个子项执行前实时复核”策略。撤权后未开始的子项跳过，
     * 已经完成的真实平台结果不回滚，避免后台任务绕过人员停用、功能撤权或店铺范围收窄。
     */
    private boolean authorizationStillValid(Map<String, Object> job, Long accountId) {
        Long userId = number(job.get("operator_user_id"));
        Long tenantId = number(job.get("tenant_id"));
        if (userId == null) return true;
        Integer allowed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user user
                 WHERE user.id=? AND user.tenant_id=? AND user.status=1
                   AND (UPPER(user.role)='ADMIN' OR (
                        EXISTS (SELECT 1 FROM sys_user_permission permission
                                 WHERE permission.user_id=user.id AND permission.permission_code='action:goods-write')
                        AND (UPPER(user.account_scope_mode)='ALL'
                             OR EXISTS (SELECT 1 FROM sys_user_account_scope scope
                                         WHERE scope.user_id=user.id AND scope.xianyu_account_id=?)
                             OR EXISTS (SELECT 1 FROM sys_user_account_group_scope group_scope
                                         JOIN xianyu_account_group_member member
                                           ON member.tenant_id=group_scope.tenant_id AND member.group_id=group_scope.group_id
                                        WHERE group_scope.user_id=user.id AND member.xianyu_account_id=?))
                   ))
                """, Integer.class, userId, tenantId, accountId, accountId);
        return allowed != null && allowed > 0;
    }

    private boolean acquireRateSlot(Long tenantId, Long accountId, int perMinute) {
        long delayMs = Math.max(2000L, 60000L / Math.max(1, Math.min(perMinute, 30)));
        jdbcTemplate.update("INSERT IGNORE INTO xianyu_goods_batch_rate_limit(tenant_id,xianyu_account_id) VALUES (?,?)", tenantId, accountId);
        return jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_rate_limit SET next_allowed_time=DATE_ADD(NOW(3),INTERVAL ? MICROSECOND)
                 WHERE tenant_id=? AND xianyu_account_id=? AND (next_allowed_time IS NULL OR next_allowed_time<=NOW(3))
                """, delayMs * 1000L, tenantId, accountId) == 1;
    }

    private void updateRateOutcome(Long tenantId, Long accountId, boolean success, int perMinute) {
        if (success) {
            jdbcTemplate.update("UPDATE xianyu_goods_batch_rate_limit SET consecutive_failures=0 WHERE tenant_id=? AND xianyu_account_id=?", tenantId, accountId);
        } else {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_rate_limit
                       SET consecutive_failures=LEAST(consecutive_failures+1,8),
                           next_allowed_time=DATE_ADD(NOW(3),INTERVAL LEAST(300,POW(2,consecutive_failures+1)*5) SECOND)
                     WHERE tenant_id=? AND xianyu_account_id=?
                    """, tenantId, accountId);
        }
    }

    private void cancelQueuedItems(Long tenantId, Long jobId) {
        jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item SET status='CANCELLED',outcome_state='NOT_EXECUTED',
                       cancelled_time=NOW(3),completed_time=NOW(3)
                 WHERE tenant_id=? AND batch_job_id=? AND status='QUEUED'
                """, tenantId, jobId);
    }

    private void notifyCompletion(Long jobId, Long tenantId, String status, long success, long failed,
                                  long unknown, long skipped, long cancelled) {
        int claimed = jdbcTemplate.update("UPDATE xianyu_goods_batch_job SET notification_sent=1 WHERE tenant_id=? AND id=? AND notification_sent=0",
                tenantId, jobId);
        if (claimed != 1) return;
        String event = "SUCCEEDED".equals(status) ? "PRODUCT_BATCH_SUCCEEDED"
                : "FAILED".equals(status) ? "PRODUCT_BATCH_FAILED" : "PRODUCT_BATCH_PARTIAL";
        String channel = jdbcTemplate.queryForObject(
                "SELECT execution_channel FROM xianyu_goods_batch_job WHERE tenant_id=? AND id=?",
                String.class, tenantId, jobId);
        Map<String, Object> evidence = Map.of("event", event, "jobId", jobId, "status", status,
                "successCount", success, "failedCount", failed, "unknownCount", unknown,
                "skippedCount", skipped, "cancelledCount", cancelled,
                "route", "QA_MOCK".equals(channel) ? "QA_TEST_SINK" : "NOTIFICATION_CENTER",
                "externalDispatched", !"QA_MOCK".equals(channel));
        jdbcTemplate.update("UPDATE xianyu_goods_batch_job SET notification_evidence_json=? WHERE tenant_id=? AND id=?",
                json(evidence), tenantId, jobId);
        if ("QA_MOCK".equals(channel)) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO xianyu_goods_event
                    (tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,data_source,
                     request_id,batch_job_id,batch_item_id,after_json)
                    SELECT item.tenant_id,item.xianyu_account_id,item.xy_goods_id,'BATCH_NOTIFICATION','SYSTEM',?,
                           'QA_MOCK',job.request_id,job.id,item.id,?
                      FROM xianyu_goods_batch_job job JOIN xianyu_goods_batch_item item ON item.batch_job_id=job.id
                     WHERE job.tenant_id=? AND job.id=? ORDER BY item.id LIMIT 1
                    """, status, json(evidence), tenantId, jobId);
            return;
        }
        notificationCenterService.dispatch(event, null, "商品批量任务" + status,
                "任务 " + jobId + "：成功 " + success + "，失败 " + failed + "，未知 " + unknown
                        + "，跳过 " + skipped + "，取消 " + cancelled,
                Map.of("jobId", jobId, "status", status, "successCount", success, "failedCount", failed,
                        "unknownCount", unknown, "skippedCount", skipped, "cancelledCount", cancelled));
    }

    private Map<String, Object> requireQaJob(Long tenantId, Long jobId) {
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                "SELECT * FROM xianyu_goods_batch_job WHERE tenant_id=? AND id=? AND execution_channel='QA_MOCK'",
                tenantId, jobId);
        if (jobs.isEmpty()) throw new BusinessException(404, "隔离 QA 任务不存在，或任务并非 QA_MOCK 通道");
        return jobs.getFirst();
    }

    private Map<String, Object> qaJobState(Long tenantId, Long jobId) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT job.id jobId,job.batch_id batchId,job.status,job.execution_channel executionChannel,
                       job.recovery_count recoveryCount,job.notification_sent notificationSent,
                       COUNT(item.id) total,
                       SUM(item.status IN ('SUCCEEDED','FAILED','UNKNOWN','SKIPPED','CANCELLED','CONFLICT')) completed,
                       SUM(item.status IN ('QUEUED','RUNNING')) active,
                       SUM(item.status='SUCCEEDED') succeeded,SUM(item.status='FAILED') failed,
                       SUM(item.status='UNKNOWN') unknownCount,SUM(item.status='SKIPPED') skipped,
                       SUM(item.status='CANCELLED') cancelled
                  FROM xianyu_goods_batch_job job JOIN xianyu_goods_batch_item item ON item.batch_job_id=job.id
                 WHERE job.tenant_id=? AND job.id=? GROUP BY job.id
                """, tenantId, jobId));
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
