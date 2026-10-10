package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** 处罚记录只读同步。优先使用闲鱼开放平台公开的 CRO 查询能力，不执行申诉或处置。 */
@Service
public class ShopRiskSyncService {

    static final String API = "alibaba.idle.cro.record.query";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 10;

    private final AccountAccessService accountAccessService;
    private final XianyuAccountMapper accountMapper;
    private final AccountService accountService;
    private final XianyuApiCallUtils apiCallUtils;
    private final AccountMatrixService accountMatrixService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ShopRiskSyncPersistenceService persistence;
    private final ConcurrentMap<String, ReentrantLock> startLocks = new ConcurrentHashMap<>();

    public ShopRiskSyncService(AccountAccessService accountAccessService,
                               XianyuAccountMapper accountMapper,
                               AccountService accountService,
                               XianyuApiCallUtils apiCallUtils,
                               AccountMatrixService accountMatrixService,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               ShopRiskSyncPersistenceService persistence) {
        this.accountAccessService = accountAccessService;
        this.accountMapper = accountMapper;
        this.accountService = accountService;
        this.apiCallUtils = apiCallUtils;
        this.accountMatrixService = accountMatrixService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.persistence = persistence;
    }

    @PostConstruct
    public void reconcileInterruptedAttempts() {
        if (jdbcTemplate == null) return;
        jdbcTemplate.update("""
                UPDATE xianyu_shop_risk_sync_attempt
                   SET status='FAILED',error_code='TIMEOUT',error_message='服务重启后处罚同步超过执行时限',
                       recovery_hint='可使用新 requestId 安全重试',completed_at=NOW(3)
                 WHERE status='RUNNING' AND started_at<DATE_SUB(NOW(3),INTERVAL 10 MINUTE)
                """);
        jdbcTemplate.update("""
                UPDATE xianyu_account_dataset_state state
                JOIN xianyu_shop_risk_sync_attempt attempt
                  ON attempt.tenant_id=state.tenant_id AND attempt.xianyu_account_id=state.xianyu_account_id
                 AND attempt.request_id=state.request_id
                   SET state.sync_status='FAILED',state.last_error_code='TIMEOUT',
                       state.last_error_message='服务重启后处罚同步超过执行时限',
                       state.recovery_hint='可使用新 requestId 安全重试'
                 WHERE state.dataset_code='SHOP_RISKS' AND attempt.error_code='TIMEOUT'
                """);
    }

    public Map<String, Object> sync(Long accountId, String requestId) {
        accountAccessService.requireAccess(accountId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 80) {
            throw new BusinessException(400, "requestId不能为空且不能超过80个字符");
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) throw new BusinessException(404, "账号不存在");
        Map<String, Object> accepted = begin(accountId, requestId);
        if (Boolean.TRUE.equals(accepted.get("idempotentReplay")) || "BUSY".equals(accepted.get("status"))) {
            return accepted;
        }
        try {
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            completeFailure(accountId, requestId, "CREDENTIAL_UNAVAILABLE", "账号登录凭据不可用，请重新扫码");
            return failed(accountId, requestId, "CREDENTIAL_UNAVAILABLE", "账号登录凭据不可用，请重新扫码");
        }

        Instant now = Instant.now();
        long cursor = now.toEpochMilli();
        int pages = 0;
        boolean hasNext = true;
        List<RiskRecord> records = new ArrayList<>();
        while (hasNext && pages < MAX_PAGES) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("pageSize", PAGE_SIZE);
            query.put("endTime", cursor);
            XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                    accountId, API, "2.0", Map.of("violationRecordQry", query), cookie, null, null);
            if (!result.isSuccess()) {
                PlatformFailure failure = platformFailure(result.getErrorMessage());
                completeFailure(accountId, requestId, failure.code(), failure.message(), failure.recoveryHint());
                return failed(accountId, requestId, failure.code(), failure.message(), failure.recoveryHint());
            }
            ParsedPage page;
            try {
                page = parse(result.getResponse());
            } catch (Exception e) {
                completeFailure(accountId, requestId, "INVALID_PLATFORM_RESPONSE", "平台处罚响应格式无法核验");
                return failed(accountId, requestId, "INVALID_PLATFORM_RESPONSE", "平台处罚响应格式无法核验");
            }
            records.addAll(page.records());
            pages++;
            hasNext = page.hasNext();
            if (!hasNext || page.records().isEmpty()) break;
            long next = page.records().stream().map(RiskRecord::penaltyTime)
                    .filter(value -> value != null).mapToLong(Instant::toEpochMilli).min().orElse(cursor);
            if (next >= cursor) break;
            cursor = next;
        }

        String coverage = hasNext ? "PARTIAL" : "FULL";
        for (RiskRecord record : records) {
            accountMatrixService.upsertRisk(accountId, new AccountMatrixService.RiskEventInput(
                    requestId, "cro:" + record.platformId(), record.platformId(), record.ruleCode(), record.title(),
                    record.severity(), record.reason(), record.status(), record.penaltyTime(), record.penaltyTime(),
                    null, record.appealStatus(), record.punishResult(),
                    "请在闲鱼安全中心核对原始通知；系统不会自动申诉、删除或上下架商品。",
                    "PLATFORM_API", coverage, now));
        }
        persistence.completeSuccess(accountId, requestId, coverage, records.size(), pages, now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("requestId", requestId);
        response.put("status", "SUCCEEDED");
        response.put("source", "PLATFORM_API");
        response.put("coverageStatus", coverage);
        response.put("recordCount", records.size());
        response.put("pageCount", pages);
        response.put("syncedAt", now);
        return response;
        } catch (Exception e) {
            safeCompleteFailure(accountId, requestId, "UNAVAILABLE", "处罚同步执行失败");
            return failed(accountId, requestId, "UNAVAILABLE", "处罚同步执行失败");
        }
    }

    private Map<String, Object> begin(Long accountId, String requestId) {
        String lockKey = tenant() + ":" + accountId;
        ReentrantLock lock = startLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Object> replay = findAttempt(accountId, requestId);
            if (!replay.isEmpty()) {
                replay.put("idempotentReplay", true);
                return replay;
            }
            if (hasRunningAttempt(accountId)) {
                return new LinkedHashMap<>(Map.of(
                        "accountId", accountId,
                        "requestId", requestId,
                        "status", "BUSY",
                        "message", "该账号已有处罚同步任务",
                        "idempotentReplay", false));
            }
            try {
                if (!persistence.createAttempt(accountId, requestId, "MANUAL")) {
                    Map<String, Object> raced = findAttempt(accountId, requestId);
                    if (!raced.isEmpty()) {
                        raced.put("idempotentReplay", true);
                        return raced;
                    }
                    return new LinkedHashMap<>(Map.of(
                            "accountId", accountId,
                            "requestId", requestId,
                            "status", "BUSY",
                            "message", "该账号已有处罚同步任务",
                            "idempotentReplay", false));
                }
            } catch (DataIntegrityViolationException race) {
                Map<String, Object> raced = findAttempt(accountId, requestId);
                if (!raced.isEmpty()) {
                    raced.put("idempotentReplay", true);
                    return raced;
                }
                throw race;
            }
            Map<String, Object> running = new LinkedHashMap<>();
            running.put("accountId", accountId);
            running.put("requestId", requestId);
            running.put("status", "RUNNING");
            running.put("source", "NONE");
            running.put("coverageStatus", "UNSYNCED");
            running.put("idempotentReplay", false);
            return running;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> findAttempt(Long accountId, String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_id requestId,status,source,coverage_status coverageStatus,
                       record_count recordCount,page_count pageCount,started_at startedAt,
                       completed_at completedAt,error_code errorCode,error_message errorMessage,
                       recovery_hint recoveryHint
                  FROM xianyu_shop_risk_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=? LIMIT 1
                """, tenant(), accountId, requestId);
        if (rows.isEmpty()) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("accountId", accountId);
        result.replaceAll((key, value) -> value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : value);
        return result;
    }

    private boolean hasRunningAttempt(Long accountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_shop_risk_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND status='RUNNING'
                """, Integer.class, tenant(), accountId);
        return count != null && count > 0;
    }

    ParsedPage parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode payload = root.path("data");
        JsonNode list = firstArray(payload,
                "/data/idle_violation_list", "/data/idleViolationList", "/idle_violation_list",
                "/idleViolationList", "/data", "/list");
        List<RiskRecord> records = new ArrayList<>();
        if (list != null) {
            for (JsonNode node : list) {
                String id = text(node, "id", "platformPenaltyId");
                if (id == null) continue;
                Instant punished = instant(node, "punish_time", "punishTime");
                Instant ends = instant(node, "punish_end_time", "punishEndTime");
                String reason = text(node, "reason");
                String result = text(node, "punish_result", "punishResult");
                int points = integer(node, "point", "point_punish", "pointPunish");
                records.add(new RiskRecord(id, text(node, "rule_code", "ruleCode"),
                        fallback(text(node, "title", "rule_name", "ruleName"), "闲鱼平台处罚"), reason,
                        result, severity(points, reason, result), status(ends),
                        integer(node, "is_appeal_front", "isAppealFront") > 0 ? "AVAILABLE" : "NOT_AVAILABLE",
                        punished));
            }
        }
        boolean hasNext = bool(payload, "has_next_page", "hasNextPage");
        return new ParsedPage(List.copyOf(records), hasNext);
    }

    private JsonNode firstArray(JsonNode node, String... pointers) {
        for (String pointer : pointers) {
            JsonNode value = node.at(pointer);
            if (value.isArray()) return value;
        }
        return null;
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isValueNode() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private int integer(JsonNode node, String... fields) {
        for (String field : fields) if (node.path(field).canConvertToInt()) return node.path(field).asInt();
        return 0;
    }

    private Instant instant(JsonNode node, String... fields) {
        for (String field : fields) {
            long value = node.path(field).asLong(0);
            if (value > 0) return Instant.ofEpochMilli(value < 10_000_000_000L ? value * 1000 : value);
        }
        return null;
    }

    private boolean bool(JsonNode node, String... fields) {
        for (String field : fields) if (!node.path(field).isMissingNode()) return node.path(field).asBoolean(false);
        return false;
    }

    private String severity(int points, String... text) {
        String value = String.join(" ", text).toLowerCase(Locale.ROOT);
        if (value.contains("冻结") || value.contains("封禁") || value.contains("查封")) return "CRITICAL";
        if (points > 0 || value.contains("限制交易") || value.contains("限制发布")) return "HIGH";
        return "WARNING";
    }

    private String status(Instant end) {
        if (end == null) return "UNKNOWN";
        return end.isAfter(Instant.now()) ? "ACTIVE" : "RESOLVED";
    }

    private void completeFailure(Long accountId, String requestId, String code, String message) {
        completeFailure(accountId, requestId, code, message,
                "请核对登录凭据和闲鱼开放平台权限；现有历史快照不会被覆盖");
    }

    private void completeFailure(Long accountId, String requestId, String code, String message, String recoveryHint) {
        persistence.completeFailure(accountId, requestId, code, message,
                recoveryHint);
    }

    private void safeCompleteFailure(Long accountId, String requestId, String code, String message) {
        try {
            completeFailure(accountId, requestId, code, message);
        } catch (Exception ignored) {
            // 终态持久化异常不能触发第二次平台调用；同 requestId 回放仍由尝试唯一键保护。
        }
    }

    private Map<String, Object> failed(Long accountId, String requestId, String code, String message) {
        return failed(accountId, requestId, code, message, null);
    }

    private Map<String, Object> failed(Long accountId, String requestId, String code, String message,
                                       String recoveryHint) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("requestId", requestId);
        response.put("status", "FAILED");
        response.put("source", "PLATFORM_API");
        response.put("coverageStatus", "UNSYNCED");
        response.put("errorCode", code);
        response.put("errorMessage", message);
        if (recoveryHint != null) response.put("recoveryHint", recoveryHint);
        response.put("idempotentReplay", false);
        return response;
    }

    private PlatformFailure platformFailure(String rawMessage) {
        String raw = rawMessage == null ? "" : rawMessage;
        if (raw.contains("FAIL_SYS_API_NOT_FOUNDED") || raw.contains("请求API不存在")) {
            return new PlatformFailure(
                    "PLATFORM_API_NOT_AVAILABLE",
                    "闲鱼返回 FAIL_SYS_API_NOT_FOUNDED（请求 API 不存在）：当前处罚查询接口不可用，尚未确定平台原因或所需权限",
                    "接口不存在本身不能证明是登录或主体权限问题；不要据此重复扫码或开通未经确认的权限。可先在闲鱼 App「我的 → 安全中心 → 违规记录」查看，并通过官方渠道确认当前支持的接口及接入条件");
        }
        if (raw.contains("TOKEN") || raw.contains("SESSION") || raw.contains("NEED_LOGIN")
                || raw.contains("令牌") || raw.contains("登录")) {
            return new PlatformFailure(
                    "CREDENTIAL_EXPIRED",
                    "闲鱼登录凭据已失效，平台未执行处罚查询",
                    "请重新扫码登录该闲鱼账号后再刷新；现有历史快照不会被覆盖");
        }
        if (raw.startsWith("FAIL_SYS_NO_PERMISSION") || raw.startsWith("FAIL_SYS_ACCESS_DENIED")) {
            return new PlatformFailure(
                    "PLATFORM_AUTHORIZATION_REQUIRED",
                    "平台明确返回处罚查询接口的访问权限拒绝",
                    "请通过官方渠道确认该接口的接入条件与当前授权；现有历史快照不会被覆盖");
        }
        return new PlatformFailure(
                "PLATFORM_REQUEST_REJECTED",
                "平台拒绝了处罚查询请求，具体原因尚未核实",
                "请查看当前失败原因并通过官方渠道确认支持的接口；不要将未知拒绝当作缺少权限。现有历史快照不会被覆盖");
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record PlatformFailure(String code, String message, String recoveryHint) {}

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    record ParsedPage(List<RiskRecord> records, boolean hasNext) {}
    record RiskRecord(String platformId, String ruleCode, String title, String reason, String punishResult,
                      String severity, String status, String appealStatus, Instant penaltyTime) {}
}
