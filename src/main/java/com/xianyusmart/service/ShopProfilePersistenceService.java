package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

/** 店铺画像同步的短事务写入边界；成功数据、状态、通道与审计原子提交。 */
@Service
public class ShopProfilePersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OperationLogService operationLogService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ShopProfilePersistenceService(JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper,
                                         OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.operationLogService = operationLogService;
        this.clock = Clock.systemUTC();
    }

    ShopProfilePersistenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                  OperationLogService operationLogService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.operationLogService = operationLogService;
        this.clock = clock;
    }

    @Transactional
    public void createAttempt(Long accountId, String requestId, String triggerType) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_profile_sync_attempt
                (tenant_id,xianyu_account_id,request_id,trigger_type,status,source,coverage_status,started_at)
                VALUES (?,?,?,?, 'QUEUED','NONE','UNSYNCED',NOW(3))
                """, tenant(), accountId, requestId, triggerType);
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id,xianyu_account_id,dataset_code,source,sync_status,coverage_status,
                 last_attempt_time,started_at,request_id)
                VALUES (?,?, 'SHOP_PROFILE','NONE','QUEUED','UNSYNCED',NOW(3),NOW(3),?)
                ON DUPLICATE KEY UPDATE sync_status='QUEUED',last_attempt_time=NOW(3),started_at=NOW(3),
                 last_error_code=NULL,last_error_message=NULL,retry_after=NULL,recovery_hint=NULL,request_id=VALUES(request_id)
                """, tenant(), accountId, requestId);
    }

    @Transactional
    public void markRunning(Long accountId, String requestId) {
        jdbcTemplate.update("""
                UPDATE xianyu_shop_profile_sync_attempt SET status='RUNNING'
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=? AND status='QUEUED'
                """, tenant(), accountId, requestId);
        jdbcTemplate.update("""
                UPDATE xianyu_account_dataset_state SET sync_status='RUNNING',started_at=NOW(3)
                 WHERE tenant_id=? AND xianyu_account_id=? AND dataset_code='SHOP_PROFILE' AND request_id=?
                """, tenant(), accountId, requestId);
    }

    @Transactional
    public void completeSuccess(Long accountId, String requestId, String source,
                                ShopProfileParser.ParsedProfile parsed) {
        Map<String, Object> previous = latestSuccessful(accountId);
        Map<String, ShopProfileParser.FieldEvidence> evidence = new LinkedHashMap<>(parsed.fieldEvidence());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("shopNickname", parsed.shopNickname()); values.put("avatarUrl", parsed.avatarUrl());
        values.put("region", parsed.region()); values.put("shopLevel", parsed.shopLevel());
        values.put("shopScore", parsed.shopScore()); values.put("superSeller", bool(parsed.superSeller()));
        values.put("sellerCredit", parsed.sellerCredit()); values.put("buyerCredit", parsed.buyerCredit());
        values.put("sesameVerified", bool(parsed.sesameVerified())); values.put("realNameVerified", bool(parsed.realNameVerified()));
        values.put("xianyuExpert", bool(parsed.xianyuExpert())); values.put("userType", parsed.userType());
        values.put("xianyuUpgraded", bool(parsed.xianyuUpgraded())); values.put("taobaoBound", bool(parsed.taobaoBound()));
        values.put("pinLimit", parsed.pinLimit()); values.put("onSaleCount", parsed.onSaleCount());
        values.put("soldCount", parsed.soldCount()); values.put("followerCount", parsed.followerCount());
        values.put("positiveRate", parsed.positiveRate()); values.put("totalReviewCount", parsed.totalReviewCount());
        values.put("totalBoughtCount", parsed.totalBoughtCount()); values.put("totalSoldCount", parsed.totalSoldCount());
        values.put("lastActiveTime", null); values.put("platformOnlineStatus", null);
        values.put("shopAuthorization", null);
        values.replaceAll((field, value) -> stale(field, value, previous, evidence));
        Map<String, Object> safeRaw = new LinkedHashMap<>(parsed.safeRawEvidence());
        safeRaw.put("fieldEvidence", evidence);
        String rawJson = json(safeRaw);
        String rawHash = sha256(rawJson);
        Instant now = clock.instant();

        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_profile_snapshot
                (tenant_id,xianyu_account_id,source,sync_status,coverage_status,request_id,
                 shop_nickname,avatar_url,shop_home_url,region,shop_level,shop_score,super_seller,
                 seller_credit,buyer_credit,sesame_verified,real_name_verified,xianyu_expert,user_type,
                 xianyu_upgraded,taobao_bound,pin_limit,on_sale_count,sold_count,follower_count,positive_rate,
                 total_review_count,total_bought_count,total_sold_count,last_active_time,synced_at,
                 raw_snapshot_hash,raw_snapshot_json,field_evidence_json,collector_version,
                 platform_online_status,certification_labels_json,platform_identity_json,shop_authorization_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant(), accountId, source, "SUCCEEDED", parsed.coverageStatus(), requestId,
                values.get("shopNickname"), values.get("avatarUrl"), parsed.shopHomeUrl(), values.get("region"), values.get("shopLevel"),
                values.get("shopScore"), values.get("superSeller"), values.get("sellerCredit"), values.get("buyerCredit"),
                values.get("sesameVerified"), values.get("realNameVerified"), values.get("xianyuExpert"),
                values.get("userType"), values.get("xianyuUpgraded"), values.get("taobaoBound"), values.get("pinLimit"),
                values.get("onSaleCount"), values.get("soldCount"), values.get("followerCount"), values.get("positiveRate"), values.get("totalReviewCount"),
                values.get("totalBoughtCount"), values.get("totalSoldCount"), values.get("lastActiveTime"), Timestamp.from(now), rawHash, rawJson,
                json(evidence), ShopProfileParser.COLLECTOR_VERSION, values.get("platformOnlineStatus"), json(certificationLabels(parsed, values)),
                json(parsed.platformIdentity()), values.get("shopAuthorization") == null
                        ? json(Map.of("status", "UNSUPPORTED")) : values.get("shopAuthorization"));

        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id,xianyu_account_id,dataset_code,source,sync_status,coverage_status,as_of_time,
                 last_attempt_time,started_at,last_success_time,last_error_code,last_error_message,retry_after,recovery_hint,request_id)
                VALUES (?,?, 'SHOP_PROFILE',?,'SUCCEEDED',?,NOW(3),NOW(3),NOW(3),NOW(3),NULL,NULL,NULL,NULL,?)
                ON DUPLICATE KEY UPDATE source=VALUES(source),sync_status='SUCCEEDED',coverage_status=VALUES(coverage_status),
                 as_of_time=VALUES(as_of_time),last_attempt_time=VALUES(last_attempt_time),
                 last_success_time=VALUES(last_success_time),last_error_code=NULL,last_error_message=NULL,
                 retry_after=NULL,recovery_hint=NULL,request_id=VALUES(request_id)
                """, tenant(), accountId, source, parsed.coverageStatus(), requestId);

        jdbcTemplate.update("""
                INSERT INTO xianyu_account_access_channel
                (tenant_id,xianyu_account_id,channel_code,channel_name,connection_status,authorization_status,
                 authorization_scope,capabilities_json,source,coverage_status,last_checked_time,last_success_time)
                VALUES (?,?, 'PROFILE_READ','店铺画像只读','CONNECTED','NOT_APPLICABLE',
                 '仅个人页画像只读接口；不包含任何平台写能力',? ,?,?,NOW(3),NOW(3))
                ON DUPLICATE KEY UPDATE connection_status='CONNECTED',authorization_status='NOT_APPLICABLE',
                 authorization_scope=VALUES(authorization_scope),capabilities_json=VALUES(capabilities_json),
                 source=VALUES(source),coverage_status=VALUES(coverage_status),last_checked_time=NOW(3),
                 last_success_time=NOW(3),last_error_code=NULL,last_error_message=NULL
                """, tenant(), accountId, json(Map.of("shopProfileRead", true, "platformWrite", false)),
                source, parsed.coverageStatus());

        jdbcTemplate.update("""
                UPDATE xianyu_shop_profile_sync_attempt
                   SET status='SUCCEEDED',source=?,coverage_status=?,completed_at=NOW(3),
                       error_code=NULL,error_message=NULL,retry_after=NULL,recovery_hint=NULL
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=?
                """, source, parsed.coverageStatus(), tenant(), accountId, requestId);
        audit(accountId, requestId, 1, "PLATFORM_CONFIRMED", source, null, null,
                Map.of("coverageStatus", parsed.coverageStatus(), "rawSnapshotHash", rawHash));
    }

    @Transactional
    public void completeFailure(Long accountId, String requestId, String source,
                                String errorCode, String errorMessage, String recoveryHint) {
        Instant retryAfter = retryAfterFor(errorCode);
        jdbcTemplate.update("""
                UPDATE xianyu_shop_profile_sync_attempt
                   SET status='FAILED',source=?,coverage_status='UNSYNCED',completed_at=NOW(3),retry_after=?,
                       error_code=?,error_message=?,recovery_hint=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=?
                """, source == null ? "NONE" : source, Timestamp.from(retryAfter), errorCode,
                safe(errorMessage), safe(recoveryHint), tenant(), accountId, requestId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id,xianyu_account_id,dataset_code,source,sync_status,coverage_status,last_attempt_time,
                 started_at,last_error_code,last_error_message,retry_after,recovery_hint,request_id)
                VALUES (?,?, 'SHOP_PROFILE',?,'FAILED','UNSYNCED',NOW(3),NOW(3),?,?,?,?,?)
                ON DUPLICATE KEY UPDATE source=VALUES(source),sync_status='FAILED',
                 last_attempt_time=NOW(3),last_error_code=VALUES(last_error_code),
                 last_error_message=VALUES(last_error_message),retry_after=VALUES(retry_after),
                 recovery_hint=VALUES(recovery_hint),request_id=VALUES(request_id)
                """, tenant(), accountId, source == null ? "NONE" : source, errorCode,
                safe(errorMessage), Timestamp.from(retryAfter), safe(recoveryHint), requestId);
        audit(accountId, requestId, 0, "PLATFORM_FAILED", source == null ? "NONE" : source,
                errorCode, errorMessage, null);
    }

    private Map<String, Object> latestSuccessful(Long accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,synced_at syncedAt,field_evidence_json fieldEvidenceJson,
                       shop_nickname shopNickname,avatar_url avatarUrl,region,shop_level shopLevel,
                       shop_score shopScore,super_seller superSeller,seller_credit sellerCredit,buyer_credit buyerCredit,
                       sesame_verified sesameVerified,real_name_verified realNameVerified,xianyu_expert xianyuExpert,
                       user_type userType,xianyu_upgraded xianyuUpgraded,taobao_bound taobaoBound,pin_limit pinLimit,
                       on_sale_count onSaleCount,sold_count soldCount,follower_count followerCount,
                       positive_rate positiveRate,total_review_count totalReviewCount,total_bought_count totalBoughtCount,
                       total_sold_count totalSoldCount,last_active_time lastActiveTime,
                       platform_online_status platformOnlineStatus,shop_authorization_json shopAuthorization
                  FROM xianyu_shop_profile_snapshot
                 WHERE tenant_id=? AND xianyu_account_id=? AND sync_status='SUCCEEDED'
                 ORDER BY created_time DESC,id DESC LIMIT 1
                """, tenant(), accountId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Object stale(String field, Object current, Map<String, Object> previous,
                         Map<String, ShopProfileParser.FieldEvidence> evidence) {
        if (current != null) return current;
        Object prior = previous.get(field);
        ShopProfileParser.FieldEvidence priorEvidence = previousEvidence(previous).get(field);
        if (prior != null && priorEvidence != null
                && ("VERIFIED".equals(priorEvidence.status()) || "STALE".equals(priorEvidence.status()))) {
            String observedAt = priorEvidence.observedAt() != null ? priorEvidence.observedAt()
                    : String.valueOf(previous.get("syncedAt"));
            evidence.put(field, new ShopProfileParser.FieldEvidence("STALE", "LOCAL_SNAPSHOT", priorEvidence.path(),
                    "平台本次未返回，沿用快照 " + previous.get("id") + " 的已核验值；原观察时间 " + observedAt,
                    observedAt));
            return prior;
        }
        return null;
    }

    private Map<String, ShopProfileParser.FieldEvidence> previousEvidence(Map<String, Object> previous) {
        Object raw = previous.get("fieldEvidenceJson");
        if (raw == null) return Map.of();
        try {
            return objectMapper.readValue(String.valueOf(raw),
                    new TypeReference<Map<String, ShopProfileParser.FieldEvidence>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void audit(Long accountId, String requestId, int status, String outcome, String source,
                       String errorCode, String errorMessage, Object diff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType("PROFILE_SYNC");
        log.setOperationModule("账号资产");
        log.setOperationDesc(status == 1 ? "同步真实店铺画像" : "真实店铺画像同步失败");
        log.setOperationStatus(status);
        log.setTargetType("SHOP_PROFILE");
        log.setTargetId(String.valueOf(accountId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource(source);
        log.setErrorMessage(errorMessage);
        log.setResponseResult(errorCode);
        log.setFieldDiffJson(json(diff));
        operationLogService.logRequired(log);
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "画像证据序列化失败", e);
        }
    }

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算画像证据摘要", e);
        }
    }

    private Integer bool(Boolean value) {
        return value == null ? null : value ? 1 : 0;
    }

    private List<String> certificationLabels(ShopProfileParser.ParsedProfile parsed, Map<String, Object> values) {
        LinkedHashSet<String> labels = new LinkedHashSet<>(parsed.certificationLabels());
        if (Integer.valueOf(1).equals(values.get("sesameVerified"))) labels.add("芝麻信用认证");
        if (Integer.valueOf(1).equals(values.get("realNameVerified"))) labels.add("实名认证");
        if (Integer.valueOf(1).equals(values.get("xianyuExpert"))) labels.add("闲鱼玩家");
        if (values.get("sellerCredit") != null) labels.add(String.valueOf(values.get("sellerCredit")));
        if (values.get("buyerCredit") != null) labels.add(String.valueOf(values.get("buyerCredit")));
        return List.copyOf(labels);
    }

    private String safe(String value) {
        if (value == null) return null;
        String first = value.lines().findFirst().orElse(value)
                .replaceAll("(?i)((?:cookie|token|sign|authorization)\\s*[:=]\\s*)([^\\s,;&]+)", "$1[REDACTED]");
        return first.length() <= 500 ? first : first.substring(0, 500);
    }

    private Duration retryDelay(String errorCode) {
        if ("CREDENTIAL_EXPIRED".equals(errorCode) || "VERIFICATION_REQUIRED".equals(errorCode)) {
            return Duration.ofHours(12);
        }
        if ("RATE_LIMITED".equals(errorCode)) return Duration.ofHours(2);
        return Duration.ofMinutes(30);
    }

    Instant retryAfterFor(String errorCode) {
        return clock.instant().plus(retryDelay(errorCode));
    }
}
