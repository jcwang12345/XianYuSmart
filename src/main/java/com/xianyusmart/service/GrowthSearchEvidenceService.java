package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 商机与比价的只读搜索证据。搜索快照不能替代平台实时真值。 */
@Service
public class GrowthSearchEvidenceService {

    private static final Set<String> TYPES = Set.of("KEYWORD", "PRICE_COMPARE", "SHOP");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccountAccessService accountAccessService;
    private final PlatformPublishService platformPublishService;
    private final OpportunityAnalysisService opportunityAnalysisService;
    private final OperationLogService operationLogService;

    public GrowthSearchEvidenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                       AccountAccessService accountAccessService,
                                       PlatformPublishService platformPublishService,
                                       OpportunityAnalysisService opportunityAnalysisService,
                                       OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accountAccessService = accountAccessService;
        this.platformPublishService = platformPublishService;
        this.opportunityAnalysisService = opportunityAnalysisService;
        this.operationLogService = operationLogService;
    }

    public Map<String, Object> search(Map<String, Object> request) {
        String requestId = requestId(request);
        String type = text(request.get("searchType")).toUpperCase();
        if (!TYPES.contains(type)) throw new BusinessException(400, "searchType 仅支持 KEYWORD、PRICE_COMPARE 或 SHOP");
        Long accountId = number(request.get("accountId"));
        if (accountId == null) throw new BusinessException(400, "请选择用于只读搜索的账号");
        accountAccessService.requireAccess(accountId);
        String query = text(request.get("query"));
        if (query.isBlank() || query.length() > 500) throw new BusinessException(400, "搜索词或店铺链接不能为空且不能超过500字符");
        if ("SHOP".equals(type) && !query.startsWith("https://")) throw new BusinessException(400, "店铺采集仅支持 HTTPS 链接");
        int pageNumber = Math.max(1, intValue(request.get("pageNumber"), 1));
        int pageSize = Math.max(1, Math.min(intValue(request.get("pageSize"), 20), 50));
        Map<String, Object> fingerprintPayload = new TreeMap<>();
        fingerprintPayload.put("searchType", type);
        fingerprintPayload.put("accountId", accountId);
        fingerprintPayload.put("query", query);
        fingerprintPayload.put("pageNumber", pageNumber);
        fingerprintPayload.put("pageSize", pageSize);
        fingerprintPayload.put("filters", map(request.get("filters")));
        String fingerprint = fingerprint(fingerprintPayload);

        List<Map<String, Object>> replay = jdbcTemplate.queryForList("""
                SELECT id,request_fingerprint FROM growth_search_snapshot
                 WHERE tenant_id=? AND request_id=?
                """, tenant(), requestId);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(text(replay.get(0).get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同搜索条件，请生成新的请求 ID");
            }
            Map<String, Object> result = get(number(replay.get(0).get("id")));
            result.put("idempotentReplay", true);
            return result;
        }

        String source = "SHOP".equals(type) ? "PLATFORM_PUBLIC_SHOP" : "PLATFORM_PUBLIC_SEARCH";
        try {
            jdbcTemplate.update("""
                    INSERT INTO growth_search_snapshot
                    (tenant_id,request_id,request_fingerprint,search_type,xianyu_account_id,query_text,filter_json,
                     source_type,authorization_status,collection_status,operator_user_id,operator_username)
                    VALUES (?,?,?,?,?,?,?,?,'USER_AUTHORIZED_READ','PENDING',?,?)
                    """, tenant(), requestId, fingerprint, type, accountId, query, json(map(request.get("filters"))),
                    source, UserContext.getUserId(), UserContext.getUsername());
        } catch (DuplicateKeyException race) {
            List<Map<String, Object>> winner = jdbcTemplate.queryForList("""
                    SELECT id,request_fingerprint FROM growth_search_snapshot
                     WHERE tenant_id=? AND request_id=?
                    """, tenant(), requestId);
            if (winner.isEmpty() || !fingerprint.equals(text(winner.get(0).get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同搜索条件，请生成新的请求 ID");
            }
            Map<String, Object> concurrentReplay = get(number(winner.get(0).get("id")));
            concurrentReplay.put("idempotentReplay", true);
            return concurrentReplay;
        }
        Long snapshotId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        try {
            PlatformPublishService.PlatformSearchResult page = "SHOP".equals(type)
                    ? platformPublishService.crawlShop(query, accountId, pageNumber, pageSize)
                    : platformPublishService.search(query, accountId, pageNumber, pageSize);
            List<Map<String, Object>> ranked = opportunityAnalysisService.rank(query, page.items());
            int rawCount = ranked.size();
            List<Map<String, Object>> items = dedupeAndAnnotate(ranked, snapshotId, source);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", items);
            result.put("pageNumber", page.pageNumber());
            result.put("pageSize", page.pageSize());
            result.put("hasMore", page.hasMore());
            result.put("reportedTotal", page.total());
            result.put("priceEvidence", priceEvidence(items));
            result.put("notice", "结论仅基于本次已返回样本；结果不是全站完整成交价，也不构成自动定价建议。");
            Instant collectedAt = Instant.now();
            Instant expiresAt = collectedAt.plus(6, ChronoUnit.HOURS);
            jdbcTemplate.update("""
                    UPDATE growth_search_snapshot
                       SET collection_status='SUCCEEDED',sample_count=?,reported_total=?,duplicate_count=?,
                           result_json=?,error_message=NULL,collected_time=?,expires_time=?
                     WHERE tenant_id=? AND id=?
                    """, items.size(), page.total(), rawCount - items.size(), json(result), Timestamp.from(collectedAt),
                    Timestamp.from(expiresAt), tenant(), snapshotId);
            Map<String, Object> snapshot = get(snapshotId);
            audit(snapshotId, accountId, requestId, "SUCCEEDED", fingerprintPayload, snapshot);
            return snapshot;
        } catch (Exception e) {
            String error = trim(e.getMessage());
            jdbcTemplate.update("""
                    UPDATE growth_search_snapshot SET collection_status='FAILED',error_message=?,collected_time=?
                     WHERE tenant_id=? AND id=?
                    """, error, Timestamp.from(Instant.now()), tenant(), snapshotId);
            audit(snapshotId, accountId, requestId, "FAILED", fingerprintPayload,
                    Map.of("status", "FAILED", "error", error));
            throw e instanceof BusinessException business ? business
                    : new BusinessException(502, "只读搜索失败，证据已保留：" + error);
        }
    }

    public List<Map<String, Object>> list(Long accountId, String searchType, int limit) {
        if (accountId != null) accountAccessService.requireAccess(accountId);
        String type = text(searchType).toUpperCase();
        if (!type.isBlank() && !TYPES.contains(type)) throw new BusinessException(400, "搜索类型无效");
        int safeLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(tenant());
        if (accountId != null) {
            where.append(" AND xianyu_account_id=?");
            params.add(accountId);
        } else {
            appendAccountScope(where, params);
        }
        if (!type.isBlank()) {
            where.append(" AND search_type=?");
            params.add(type);
        }
        params.add(safeLimit);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id FROM growth_search_snapshot
                """ + where + " ORDER BY created_time DESC,id DESC LIMIT ?", params.toArray());
        return rows.stream()
                .map(row -> get(number(row.get("id"))))
                .toList();
    }

    private void appendAccountScope(StringBuilder where, List<Object> params) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return;
        if (scope.accountIds().isEmpty()) {
            where.append(" AND 1=0");
            return;
        }
        where.append(" AND xianyu_account_id IN (");
        boolean first = true;
        for (Long allowed : scope.accountIds().stream().sorted().toList()) {
            if (!first) where.append(',');
            where.append('?');
            params.add(allowed);
            first = false;
        }
        where.append(')');
    }

    public Map<String, Object> get(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,request_id,request_fingerprint,search_type,xianyu_account_id,query_text,filter_json,
                       source_type,authorization_status,collection_status,sample_count,reported_total,duplicate_count,
                       result_json,error_message,collected_time,expires_time,operator_username,created_time,updated_time
                  FROM growth_search_snapshot WHERE tenant_id=? AND id=?
                """, tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "搜索证据不存在");
        Map<String, Object> raw = rows.get(0);
        Long accountId = number(raw.get("xianyu_account_id"));
        accountAccessService.requireAccess(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("requestId", raw.get("request_id"));
        result.put("requestFingerprint", raw.get("request_fingerprint"));
        result.put("searchType", raw.get("search_type"));
        result.put("accountId", accountId);
        result.put("query", raw.get("query_text"));
        result.put("filters", jsonMap(text(raw.get("filter_json"))));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", raw.get("source_type"));
        evidence.put("authorizationStatus", raw.get("authorization_status"));
        evidence.put("collectedAt", instant(raw.get("collected_time")));
        evidence.put("expiresAt", instant(raw.get("expires_time")));
        evidence.put("freshness", freshness(instant(raw.get("expires_time"))));
        evidence.put("sampleCount", raw.get("sample_count"));
        evidence.put("reportedTotal", raw.get("reported_total"));
        evidence.put("duplicateCount", raw.get("duplicate_count"));
        result.put("evidence", evidence);
        result.put("status", raw.get("collection_status"));
        result.put("result", jsonMap(text(raw.get("result_json"))));
        result.put("error", raw.get("error_message"));
        result.put("operatorUsername", raw.get("operator_username"));
        result.put("createdTime", instant(raw.get("created_time")));
        result.put("updatedTime", instant(raw.get("updated_time")));
        result.put("idempotentReplay", false);
        return result;
    }

    private List<Map<String, Object>> dedupeAndAnnotate(List<Map<String, Object>> candidates,
                                                         Long snapshotId, String source) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, Object> candidate : candidates) {
            String itemId = text(candidate.get("itemId"));
            String sourceUrl = text(candidate.get("sourceUrl"));
            String key = !itemId.isBlank() ? "ID:" + itemId : "URL:" + sourceUrl;
            if ((itemId.isBlank() && sourceUrl.isBlank()) || !seen.add(key)) continue;
            Map<String, Object> item = new LinkedHashMap<>(candidate);
            item.put("sourceEvidence", Map.of(
                    "snapshotId", snapshotId,
                    "source", source,
                    "capturedAt", now,
                    "authorizationStatus", "USER_AUTHORIZED_READ"));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> priceEvidence(List<Map<String, Object>> items) {
        List<BigDecimal> prices = items.stream().map(item -> decimal(item.get("price")))
                .filter(java.util.Objects::nonNull).filter(price -> price.signum() >= 0)
                .sorted(Comparator.naturalOrder()).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pricedSampleCount", prices.size());
        result.put("unpricedSampleCount", items.size() - prices.size());
        if (prices.isEmpty()) {
            result.put("minimum", null);
            result.put("median", null);
            result.put("maximum", null);
            result.put("coverageStatus", "NO_PRICE_DATA");
            return result;
        }
        int middle = prices.size() / 2;
        BigDecimal median = prices.size() % 2 == 1 ? prices.get(middle)
                : prices.get(middle - 1).add(prices.get(middle)).divide(BigDecimal.valueOf(2));
        result.put("minimum", prices.get(0));
        result.put("median", median);
        result.put("maximum", prices.get(prices.size() - 1));
        result.put("coverageStatus", prices.size() == items.size() ? "COMPLETE_SAMPLE" : "PARTIAL_SAMPLE");
        return result;
    }

    private BigDecimal decimal(Object value) {
        try {
            String normalized = text(value).replace(",", "").replaceAll("[^0-9.\\-]", "");
            return normalized.matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(normalized) : null;
        } catch (Exception ignored) { return null; }
    }

    private String freshness(Instant expiresAt) {
        if (expiresAt == null) return "UNKNOWN";
        return expiresAt.isAfter(Instant.now()) ? "FRESH" : "STALE";
    }

    private void audit(Long snapshotId, Long accountId, String requestId, String status,
                       Object request, Object result) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setXianyuAccountId(accountId);
        log.setOperationType("GROWTH_SEARCH_" + status);
        log.setOperationModule("GROWTH_SEARCH");
        log.setOperationDesc("商机或比价只读搜索证据已" + ("SUCCEEDED".equals(status) ? "保存" : "记录失败"));
        log.setOperationStatus("SUCCEEDED".equals(status) ? 1 : 0);
        log.setTargetType("GROWTH_SEARCH_SNAPSHOT");
        log.setTargetId(String.valueOf(snapshotId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState("SUCCEEDED".equals(status) ? "LOCAL_SUCCESS" : "FAILED");
        log.setDataSource("PLATFORM_PUBLIC_READ");
        log.setRequestParams(json(request));
        log.setResponseResult(json(result));
        log.setFieldDiffJson(json(Map.of("collectionStatus", status)));
        log.setCreateTime(System.currentTimeMillis());
        operationLogService.logRequired(log);
    }

    private String requestId(Map<String, Object> request) {
        String value = text(request.get("requestId"));
        if (value.isBlank() || value.length() > 64) throw new BusinessException(400, "必须提供不超过64个字符的 requestId");
        return value;
    }

    private Long tenant() {
        Long tenant = TenantContext.get();
        if (tenant == null) tenant = UserContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("缺少租户上下文");
        return tenant;
    }

    private String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("无法生成搜索指纹", e); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("搜索证据无法序列化", e); }
    }

    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new TreeMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null || text(value).isBlank() ? null : Long.parseLong(text(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(text(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.atZone(java.time.ZoneId.systemDefault()).toInstant();
        return null;
    }

    private String trim(String value) {
        String text = value == null || value.isBlank() ? "未知错误" : value;
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
