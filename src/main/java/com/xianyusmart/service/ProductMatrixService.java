package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** PRD-01～04 商品驾驶舱、360 详情、事件和批量任务。 */
@Service
public class ProductMatrixService {

    private static final Set<String> STATUS_BUCKETS = Set.of("ALL", "ON_SALE", "SOLD", "OFF_SHELF", "OTHER", "DRAFT");
    private static final Set<String> BATCH_OPERATIONS = Set.of(
            "ON_SALE", "OFF_SHELF", "CHANGE_PRICE", "CHANGE_STOCK", "POLISH", "DELETE", "SYNC");
    private static final BigDecimal MAX_PRODUCT_PRICE = new BigDecimal("9999999.99");
    private static final Set<String> BATCH_STATUSES = Set.of(
            "PENDING_CONFIRMATION", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "PARTIAL_SUCCESS",
            "CANCEL_REQUESTED", "CANCELLED");
    private static final Set<String> ITEM_RETRYABLE = Set.of("FAILED");
    private static final int MAX_BATCH_ITEMS = 1000;
    private static final Map<String, String> PRODUCT_EVENT_LABELS = Map.ofEntries(
            Map.entry("LOCAL_DETAILS_EDIT", "本地商品资料已编辑"),
            Map.entry("PRODUCT_LOCAL_EDIT", "本地商品资料已编辑"),
            Map.entry("AUTOMATION_CONFIG_UPDATED", "自动化配置已更新"),
            Map.entry("AUTOMATION_CONFIG_CHANGED", "自动化配置已更新"),
            Map.entry("PRODUCT_AUTOMATION_UPDATE", "自动化配置已更新"),
            Map.entry("PRODUCT_SYNC_STARTED", "商品同步开始"),
            Map.entry("PRODUCT_SYNC_SUCCEEDED", "商品同步成功"),
            Map.entry("PRODUCT_SYNC_FAILED", "商品同步失败"),
            Map.entry("PRODUCT_SYNC_EXCEPTION", "商品同步异常"),
            Map.entry("PRODUCT_PUBLISHED", "商品已发布"),
            Map.entry("PRODUCT_PUBLISH", "商品发布"),
            Map.entry("PRODUCT_PUBLISH_FAILED", "商品发布失败"),
            Map.entry("PRODUCT_ON_SALE", "商品已上架"),
            Map.entry("PRODUCT_OFF_SHELF", "商品已下架"),
            Map.entry("PRICE_CHANGED", "商品价格已变更"),
            Map.entry("STOCK_CHANGED", "商品库存已变更"),
            Map.entry("POLISH_SUCCEEDED", "商品擦亮成功"),
            Map.entry("BATCH_ITEM_SUCCEEDED", "批量子任务成功"),
            Map.entry("BATCH_ITEM_FAILED", "批量子任务失败"),
            Map.entry("BATCH_CHANGE_PRICE", "批量商品改价"),
            Map.entry("BATCH_CHANGE_STOCK", "批量商品改库存"),
            Map.entry("BATCH_ON_SALE", "批量商品上架"),
            Map.entry("BATCH_OFF_SHELF", "批量商品下架"),
            Map.entry("BATCH_POLISH", "批量商品擦亮"),
            Map.entry("BATCH_SYNC", "批量商品同步"),
            Map.entry("BATCH_DELETE", "批量商品删除"),
            Map.entry("BATCH_NOTIFICATION", "批量任务通知已生成"),
            Map.entry("BATCH_CANCEL", "批量任务已取消"),
            Map.entry("PRODUCT_BATCH_CANCEL", "批量任务已取消"),
            Map.entry("PRODUCT_BATCH_CREATE", "批量任务已创建"),
            Map.entry("PRODUCT_BATCH_RETRY", "批量失败项已重试"),
            Map.entry("PRODUCT_BATCH_SUCCEEDED", "批量任务成功"),
            Map.entry("PRODUCT_BATCH_PARTIAL", "批量任务部分成功"),
            Map.entry("PRODUCT_BATCH_FAILED", "批量任务失败"),
            Map.entry("MARKETING_DRAFT_SAVED", "营销草稿已保存"),
            Map.entry("MARKETING_DRAFT_CHANGED", "营销草稿已保存"),
            Map.entry("MARKETING_APPLIED", "营销配置已应用"));
    private static final Map<String, String> PRODUCT_OUTCOME_LABELS = Map.ofEntries(
            Map.entry("LOCAL_SUCCESS", "本地处理成功"),
            Map.entry("PLATFORM_CONFIRMED", "平台已确认"),
            Map.entry("PLATFORM_REQUESTED", "已请求平台"),
            Map.entry("SUCCEEDED", "成功"),
            Map.entry("FAILED", "失败"),
            Map.entry("UNKNOWN", "结果未知"),
            Map.entry("SKIPPED", "已跳过"),
            Map.entry("CANCEL_REQUESTED", "正在取消"),
            Map.entry("CANCELLED", "已取消"),
            Map.entry("QA_CONFIRMED", "隔离 QA 已确认"),
            Map.entry("QA_MOCK_CONFIRMED", "隔离 QA 已确认"),
            Map.entry("QUEUED", "已排队"),
            Map.entry("RUNNING", "处理中"));
    private static final Map<String, String> PRODUCT_SOURCE_LABELS = Map.ofEntries(
            Map.entry("LOCAL", "本地记录"),
            Map.entry("LOCAL_CACHE", "本地缓存"),
            Map.entry("PLATFORM_API", "平台接口"),
            Map.entry("PLATFORM_WEB", "平台页面"),
            Map.entry("PLATFORM_LIST_SYNC", "平台列表同步"),
            Map.entry("WEBHOOK", "平台事件"),
            Map.entry("SYSTEM", "系统任务"),
            Map.entry("QA_MOCK", "隔离测试"),
            Map.entry("QA_FIXTURE", "隔离测试数据"),
            Map.entry("MULTIPLE", "多个数据源"),
            Map.entry("UNSYNCED", "尚未同步"));
    private static final Map<String, String> PRODUCT_ORIGIN_LABELS = Map.ofEntries(
            Map.entry("USER", "人工操作"),
            Map.entry("SYSTEM", "系统任务"),
            Map.entry("WEBHOOK", "平台回调"),
            Map.entry("BATCH", "批量任务"),
            Map.entry("SYNC", "同步任务"),
            Map.entry("QA", "隔离测试"));

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final ProductBatchQaMockService productBatchQaMockService;
    private final PlatformWritePolicy platformWritePolicy;

    public ProductMatrixService(JdbcTemplate jdbcTemplate,
                                NamedParameterJdbcTemplate namedJdbc,
                                AccountAccessService accountAccessService,
                                OperationLogService operationLogService,
                                ObjectMapper objectMapper,
                                ProductBatchQaMockService productBatchQaMockService,
                                PlatformWritePolicy platformWritePolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
        this.productBatchQaMockService = productBatchQaMockService;
        this.platformWritePolicy = platformWritePolicy;
    }

    public Map<String, Object> list(ProductFilter filter) {
        ProductFilter normalized = normalizeFilter(filter);
        QueryParts base = queryParts(normalized, false);
        QueryParts withStatus = queryParts(normalized, true);
        Integer total = namedJdbc.queryForObject("SELECT COUNT(*) " + withStatus.fromWhere(), withStatus.params(), Integer.class);
        int safeTotal = total == null ? 0 : total;
        int offset = Math.min((normalized.page() - 1) * normalized.pageSize(), safeTotal);
        MapSqlParameterSource pageParams = withStatus.params().addValue("limit", normalized.pageSize()).addValue("offset", offset);
        String exactMatchOrder = normalized.search() == null ? "" :
                "CASE WHEN goods.xy_good_id=:exactSearch OR goods.outer_id=:exactSearch THEN 0 ELSE 1 END, ";
        List<Map<String, Object>> records = namedJdbc.query("""
                SELECT goods.*, account.account_note, account.unb
                """ + withStatus.fromWhere() + " ORDER BY " + exactMatchOrder
                        + "goods.updated_time DESC, goods.id DESC LIMIT :limit OFFSET :offset",
                pageParams, (rs, rowNum) -> productRow(rs));
        records.forEach(product -> {
            enrichWarehouseEvidence(product);
            product.put("metric", metricWindow(((Number) product.get("accountId")).longValue(),
                    string(product.get("goodsId")), normalized.metricWindowDays()));
        });

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (String bucket : List.of("ALL", "ON_SALE", "SOLD", "OFF_SHELF", "OTHER", "DRAFT")) {
            MapSqlParameterSource params = copy(base.params());
            String predicate = statusPredicate(bucket);
            Integer count = namedJdbc.queryForObject("SELECT COUNT(*) " + base.fromWhere()
                    + (predicate.isBlank() ? "" : " AND " + predicate), params, Integer.class);
            statusCounts.put(bucket, count == null ? 0 : count);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", records);
        response.put("statusCounts", statusCounts);
        response.put("total", safeTotal);
        response.put("page", normalized.page());
        response.put("pageSize", normalized.pageSize());
        response.put("totalPages", (int) Math.ceil((double) safeTotal / normalized.pageSize()));
        response.put("summaryScope", "FILTERED_RESULT");
        response.put("summary", filteredSummary(withStatus, normalized.metricWindowDays()));
        response.put("metricWindowDays", normalized.metricWindowDays());
        Instant checkedAt = Instant.now();
        response.put("lastCheckedAt", checkedAt);
        response.put("emptyState", safeTotal == 0 ? productListEmptyState(normalized, checkedAt) : null);
        response.put("dataNotice", "商品主字段来自本地缓存；每行 source、syncStatus、coverageStatus、lastSyncedTime 表示其平台同步证据。");
        return response;
    }

    static Map<String, Object> productListEmptyState(ProductFilter filter, Instant checkedAt) {
        boolean narrowed = filter.search() != null || !filter.accountIds().isEmpty() || filter.groupId() != null
                || !"ALL".equals(filter.statusBucket()) || filter.source() != null || filter.publishChannel() != null;
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("search", filter.search());
        scope.put("accountIds", filter.accountIds());
        scope.put("groupId", filter.groupId());
        scope.put("statusBucket", filter.statusBucket());
        scope.put("source", filter.source());
        scope.put("publishChannel", filter.publishChannel());
        scope.put("metricWindowDays", filter.metricWindowDays());
        scope.put("label", narrowed ? "当前组合筛选范围" : "当前经营主体的全部可见商品");
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("title", narrowed ? "当前筛选范围没有匹配商品" : "当前范围尚无商品");
        empty.put("scope", scope);
        empty.put("lastCheckedAt", checkedAt);
        empty.put("reason", narrowed
                ? "已按当前账号、状态、来源和关键词组合检查，没有找到匹配记录。"
                : "已检查当前权限范围内的商品缓存，没有找到可验证商品记录。");
        empty.put("nextAction", narrowed
                ? "清除部分筛选条件后重试，或切换账号确认商品同步范围。"
                : "先选择账号同步商品；未同步数据不会显示成 0。"
        );
        return empty;
    }

    public Map<String, Object> capabilities(Long accountId, String goodsId) {
        Map<String, Object> product = findProduct(accountId, goodsId);
        boolean qaMock = productBatchQaMockService.isEligible(requireTenant(), accountId, goodsId);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String operation : BATCH_OPERATIONS) {
            String reason = qaMock ? null : conflict(operation, Map.of(
                    "price", product.get("sold_price") == null ? BigDecimal.ONE : product.get("sold_price"),
                    "stock", product.get("stock") == null ? 0 : product.get("stock")), product);
            result.put(operation, Map.of("available", reason == null, "reason", reason == null ? "" : reason,
                    "mode", reason == null ? qaMock ? "QA_MOCK" : "PLATFORM" : "SAFE_DEGRADATION"));
        }
        result.put("EDIT", Map.of("available", true, "mode", "LOCAL_ONLY",
                "reason", "当前平台通道未验证完整编辑协议，只允许维护本地资料并保留来源标识"));
        result.put("MARKETING", Map.of("available", true, "mode", "LOCAL_DRAFT_WITH_PREFLIGHT",
                "reason", "可配置本地营销方案并预检；真实平台写入仍按能力证据安全降级"));
        return result;
    }

    @Transactional
    public Map<String, Object> updateLocalDetails(Long accountId, String goodsId, LocalProductUpdate command) {
        requireProductAccess(accountId, goodsId);
        if (command == null) throw new BusinessException(400, "本地商品资料不能为空");
        String requestId = requireText(command.requestId(), "requestId", 80);
        Map<String, Object> before = findProduct(accountId, goodsId);
        Long expected = command.expectedVersion();
        if (expected == null || expected != longValue(before.get("row_version"))) {
            throw new BusinessException(409, "商品资料已被其他用户更新，请刷新后重试");
        }
        String title = command.title() == null ? string(before.get("title")) : requireText(command.title(), "商品标题", 500);
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_goods SET title=?,support_policy=?,location_text=?,row_version=row_version+1,
                       sync_status='LOCAL_CHANGED',coverage_status='PARTIAL'
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=? AND row_version=?
                """, title, limit(command.supportPolicy(), 1000), limit(command.location(), 255),
                requireTenant(), accountId, goodsId, expected);
        if (updated != 1) throw new BusinessException(409, "商品资料版本冲突，请刷新后重试");
        Map<String, Object> after = findProduct(accountId, goodsId);
        Map<String, Object> beforeSnapshot = localDetailsSnapshot(before);
        Map<String, Object> afterSnapshot = localDetailsSnapshot(after);
        Map<String, Object> fieldDiff = fieldDiff("LOCAL_ONLY", beforeSnapshot, afterSnapshot,
                Map.of("title", "商品标题", "supportPolicy", "支持政策", "location", "所在地"));
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_event
                (tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,data_source,
                 operator_user_id,operator_username,request_id,idempotency_key,before_json,after_json,field_diff_json)
                VALUES (?,?,?,'LOCAL_DETAILS_EDIT','USER','LOCAL_SUCCESS','LOCAL',?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, requireTenant(), accountId, goodsId, UserContext.getUserId(), UserContext.getUsername(),
                requestId, requestId, json(beforeSnapshot), json(afterSnapshot), json(fieldDiff));
        auditChange(accountId, goodsId, "PRODUCT_LOCAL_EDIT", "编辑本地商品资料", requestId,
                "LOCAL_SUCCESS", command, beforeSnapshot, afterSnapshot, fieldDiff);
        Map<String, Object> result = detail(accountId, goodsId);
        result.put("capabilityMode", "LOCAL_ONLY");
        return result;
    }

    @Transactional
    public Map<String, Object> updateAutomation(Long accountId, String goodsId, AutomationUpdate command) {
        requireProductAccess(accountId, goodsId);
        if (command == null) throw new BusinessException(400, "自动化配置不能为空");
        String requestId = requireText(command.requestId(), "requestId", 80);
        Map<String, Object> before = marketing(accountId, goodsId);
        Map<String, Object> beforeSnapshot = automationSnapshot(before);
        Map<String, Object> requestedSnapshot = automationCommandSnapshot(command);
        int reserved = jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_goods_event
                (tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,data_source,
                 operator_user_id,operator_username,request_id,idempotency_key,before_json)
                VALUES (?,?,?,'AUTOMATION_CONFIG_CHANGED','USER','LOCAL_SUCCESS','LOCAL',?,?,?,?,?)
                """, requireTenant(), accountId, goodsId, UserContext.getUserId(), UserContext.getUsername(),
                requestId, requestId, json(beforeSnapshot));
        if (reserved == 0) {
            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                    SELECT after_json FROM xianyu_goods_event
                     WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                       AND event_type='AUTOMATION_CONFIG_CHANGED' AND request_id=?
                     FOR UPDATE
                    """, requireTenant(), accountId, goodsId, requestId);
            Map<String, Object> recordedSnapshot = null;
            if (!existing.isEmpty()) {
                Object recordedAfter = readJson(string(existing.getFirst().get("after_json")));
                if (recordedAfter instanceof Map<?, ?> recordedMap) {
                    recordedSnapshot = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : recordedMap.entrySet()) {
                        recordedSnapshot.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    if (!Objects.equals(recordedSnapshot, requestedSnapshot)) {
                        throw new BusinessException(409, "requestId 已用于不同的自动化配置，请生成新的 requestId");
                    }
                }
            }
            Map<String, Object> replay = new LinkedHashMap<>(marketing(accountId, goodsId));
            // A concurrent retry can retain a pre-reservation REPEATABLE READ snapshot.
            // The locking event read observes the winner's committed automation state.
            if (recordedSnapshot != null) replay.putAll(recordedSnapshot);
            replay.put("requestId", requestId);
            replay.put("idempotentReplay", true);
            return replay;
        }
        jdbcTemplate.update("""
                INSERT INTO xianyu_goods_config
                (tenant_id,xianyu_account_id,xy_goods_id,xianyu_auto_delivery_on,xianyu_auto_reply_on,
                 xianyu_auto_rate_on,xianyu_auto_rate_content,xianyu_auto_polish_on,human_intervention_on)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE xianyu_auto_delivery_on=VALUES(xianyu_auto_delivery_on),
                 xianyu_auto_reply_on=VALUES(xianyu_auto_reply_on),xianyu_auto_rate_on=VALUES(xianyu_auto_rate_on),
                 xianyu_auto_polish_on=VALUES(xianyu_auto_polish_on),human_intervention_on=VALUES(human_intervention_on)
                """, requireTenant(), accountId, goodsId, bool(command.autoDelivery()), bool(command.autoReply()),
                bool(command.autoRate()), XianyuGoodsConfig.DEFAULT_AUTO_RATE_CONTENT,
                bool(command.autoPolish()), bool(command.humanTakeover()));
        Map<String, Object> current = marketing(accountId, goodsId);
        Map<String, Object> afterSnapshot = automationSnapshot(current);
        Map<String, Object> fieldDiff = fieldDiff("LOCAL_ONLY", beforeSnapshot, afterSnapshot, Map.of(
                "autoDeliveryEnabled", "自动发货", "autoReplyEnabled", "自动回复",
                "autoRateEnabled", "自动评价", "autoPolishEnabled", "自动擦亮",
                "humanTakeoverEnabled", "人工接管"));
        jdbcTemplate.update("""
                UPDATE xianyu_goods_event SET after_json=?,field_diff_json=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                   AND event_type='AUTOMATION_CONFIG_CHANGED' AND request_id=?
                """, json(afterSnapshot), json(fieldDiff), requireTenant(), accountId, goodsId, requestId);
        auditChange(accountId, goodsId, "PRODUCT_AUTOMATION_UPDATE", "更新商品自动化配置", requestId,
                "LOCAL_SUCCESS", command, beforeSnapshot, afterSnapshot, fieldDiff);
        current.put("requestId", requestId);
        current.put("idempotentReplay", false);
        return current;
    }

    public Map<String, Object> rawSnapshotMetadata(Long accountId, String goodsId, Long eventId) {
        requireProductAccess(accountId, goodsId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,raw_snapshot_hash,data_source,created_time FROM xianyu_goods_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND id=?
                """, requireTenant(), accountId, goodsId, eventId);
        if (rows.isEmpty()) throw new BusinessException(404, "事件不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("storageStatus", "HASH_ONLY");
        result.put("redacted", true);
        result.put("message", "系统只保留脱敏摘要，不保存 Cookie、令牌或完整敏感快照");
        return result;
    }

    public Map<String, Object> detail(Long accountId, String goodsId) {
        requireProductAccess(accountId, goodsId);
        Long tenantId = requireTenant();
        List<Map<String, Object>> products = jdbcTemplate.query("""
                SELECT goods.*, account.account_note, account.unb
                  FROM xianyu_goods goods
                  JOIN xianyu_account account ON account.id=goods.xianyu_account_id AND account.tenant_id=goods.tenant_id
                 WHERE goods.tenant_id=? AND goods.xianyu_account_id=? AND goods.xy_good_id=?
                """, (rs, rowNum) -> productRow(rs), tenantId, accountId, goodsId);
        if (products.isEmpty()) throw new BusinessException(404, "商品不存在或不属于当前经营主体");
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> basic = products.getFirst();
        enrichWarehouseEvidence(basic);
        List<Map<String, Object>> skuRows = skus(accountId, goodsId);
        Map<String, Object> skuEvidence = skuEvidence(basic, skuRows);
        basic.put("declaredSkuCount", skuEvidence.get("declaredCount"));
        basic.put("verifiedSkuCount", skuEvidence.get("verifiedCount"));
        basic.put("skuCoverageStatus", skuEvidence.get("coverageStatus"));
        basic.put("skuCoverageMessage", skuEvidence.get("message"));
        response.put("basic", basic);
        response.put("orderSummary", orderSummary(accountId, goodsId));
        response.put("skus", skuRows);
        response.put("skuEvidence", skuEvidence);
        response.put("marketing", marketing(accountId, goodsId));
        Map<String, Object> metricWindows = new LinkedHashMap<>();
        metricWindows.put("day1", metricWindow(accountId, goodsId, 1));
        metricWindows.put("day7", metricWindow(accountId, goodsId, 7));
        metricWindows.put("day30", metricWindow(accountId, goodsId, 30));
        response.put("metrics", metricWindows);
        response.put("metricTrend", metricTrend(accountId, goodsId, 30));
        List<Map<String, Object>> timeline = events(accountId, goodsId, 200);
        response.put("timeline", timeline);
        response.put("timelineState", timelineState(accountId, goodsId, timeline, Instant.now()));
        response.put("capabilities", capabilities(accountId, goodsId));
        response.put("refreshModes", List.of(
                Map.of("code", "CACHE", "label", "读取缓存", "available", true),
                Map.of("code", "PRODUCT", "label", "同步商品", "available", true),
                Map.of("code", "METRICS", "label", "同步罗盘", "available", false, "reason", "平台指标适配器未接入"),
                Map.of("code", "MARKETING", "label", "同步营销", "available", false, "reason", "平台营销适配器未接入")));
        return response;
    }

    private Map<String, Object> metricTrend(Long accountId, String goodsId, int days) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT metric_date,source,coverage_status,synced_at,
                       exposure_count,exposure_uv_count,visitor_count,click_count,favorite_count,
                       inquiry_count,inquiry_buyer_count,paid_order_count,paid_buyer_count,
                       completed_buyer_count,refund_buyer_count,refund_order_count,paid_amount,refund_amount
                  FROM xianyu_goods_metric_daily
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                   AND metric_date>=DATE_SUB(CURRENT_DATE(), INTERVAL ? DAY)
                 ORDER BY metric_date,source
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricDate", rs.getDate("metric_date").toLocalDate());
            row.put("source", rs.getString("source"));
            row.put("coverageStatus", rs.getString("coverage_status"));
            row.put("syncedAt", instant(rs, "synced_at"));
            for (String field : List.of("exposure_count", "exposure_uv_count", "visitor_count", "click_count",
                    "favorite_count", "inquiry_count", "inquiry_buyer_count", "paid_order_count",
                    "paid_buyer_count", "completed_buyer_count", "refund_buyer_count", "refund_order_count")) {
                row.put(camel(field), nullableLong(rs, field));
            }
            row.put("paidAmount", rs.getBigDecimal("paid_amount"));
            row.put("refundAmount", rs.getBigDecimal("refund_amount"));
            return row;
        }, requireTenant(), accountId, goodsId, Math.max(0, days - 1));
        return metricTrendResponse(rows, days, accountId, goodsId, LocalDate.now(), Instant.now());
    }

    static Map<String, Object> metricTrendResponse(List<Map<String, Object>> rows, int days, Long accountId,
                                                   String goodsId, LocalDate endDate, Instant checkedAt) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDate startDate = endDate.minusDays(safeDays - 1L);
        Map<LocalDate, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            LocalDate date = row.get("metricDate") instanceof LocalDate value ? value : null;
            if (date != null && !date.isBefore(startDate) && !date.isAfter(endDate)) {
                byDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> points = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        int knownDays = 0;
        int conflictDays = 0;
        Instant latestSyncedAt = null;
        for (int offset = 0; offset < safeDays; offset++) {
            LocalDate date = startDate.plusDays(offset);
            List<Map<String, Object>> candidates = byDate.getOrDefault(date, List.of());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date);
            point.put("sampled", !candidates.isEmpty());
            if (candidates.isEmpty()) {
                point.put("source", "UNSYNCED");
                point.put("coverageStatus", "UNSYNCED");
                point.put("syncedAt", null);
                copyTrendValues(point, null);
            } else if (candidates.size() > 1) {
                LinkedHashSet<String> daySources = new LinkedHashSet<>();
                candidates.forEach(row -> daySources.add(string(row.get("source"))));
                sources.addAll(daySources);
                point.put("source", "MULTIPLE");
                point.put("sources", List.copyOf(daySources));
                point.put("coverageStatus", "CONFLICT");
                point.put("syncedAt", candidates.stream().map(row -> row.get("syncedAt"))
                        .filter(Instant.class::isInstance).map(Instant.class::cast).max(Instant::compareTo).orElse(null));
                point.put("reason", "同一自然日存在多条来源记录，趋势值已隐藏以避免重复累计");
                copyTrendValues(point, null);
                conflictDays++;
            } else {
                Map<String, Object> row = candidates.getFirst();
                String source = string(row.get("source"));
                sources.add(source);
                point.put("source", source);
                point.put("coverageStatus", string(row.get("coverageStatus")));
                point.put("syncedAt", row.get("syncedAt"));
                copyTrendValues(point, row);
                knownDays++;
            }
            if (point.get("syncedAt") instanceof Instant synced
                    && (latestSyncedAt == null || synced.isAfter(latestSyncedAt))) latestSyncedAt = synced;
            points.add(point);
        }
        String source = sources.isEmpty() ? "UNSYNCED" : sources.size() == 1 ? sources.iterator().next() : "MULTIPLE";
        String status = knownDays == 0 && conflictDays == 0 ? "UNSYNCED"
                : knownDays == safeDays && conflictDays == 0 && sources.size() == 1
                && points.stream().allMatch(point -> "FULL".equals(point.get("coverageStatus"))) ? "FULL" : "PARTIAL";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", safeDays);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("knownDays", knownDays);
        result.put("conflictDays", conflictDays);
        result.put("coverageStatus", status);
        result.put("coverage", coverage(status, knownDays, safeDays));
        result.put("source", source);
        result.put("sources", List.copyOf(sources));
        result.put("syncedAt", latestSyncedAt);
        result.put("lastCheckedAt", checkedAt);
        result.put("scope", Map.of("accountId", accountId, "goodsId", goodsId,
                "label", "当前商品 · 最近 " + safeDays + " 天逐日趋势"));
        result.put("points", points);
        result.put("message", status.equals("UNSYNCED")
                ? "最近 " + safeDays + " 天没有已同步的逐日经营指标；缺失日期保持为空，不补 0。"
                : conflictDays > 0 ? "有 " + conflictDays + " 天存在多来源冲突，冲突日期已隐藏数值。"
                : "缺失日期保持为空，不补 0；每个点展示自身来源。"
        );
        return result;
    }

    private static void copyTrendValues(Map<String, Object> target, Map<String, Object> source) {
        for (String field : List.of("exposureCount", "exposureUvCount", "visitorCount", "clickCount",
                "favoriteCount", "inquiryCount", "inquiryBuyerCount", "paidOrderCount", "paidBuyerCount",
                "completedBuyerCount", "refundBuyerCount", "refundOrderCount", "paidAmount", "refundAmount")) {
            target.put(field, source == null ? null : source.get(field));
        }
    }

    private Map<String, Object> orderSummary(Long accountId, String goodsId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) totalOrders,
                       SUM(delivery_status='DELIVERED') deliveredOrders,
                       SUM(refund_status IS NOT NULL AND refund_status<>'NONE') refundOrders,
                       SUM(order_amount IS NOT NULL) knownAmountOrders,
                       SUM(order_amount) knownAmountTotal,
                       MAX(create_time) latestOrderTime,
                       MAX(last_synced_time) latestSyncedTime
                  FROM xianyu_goods_order
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, requireTenant(), accountId, goodsId);
        long total = longValue(row.get("totalOrders"));
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("coverageStatus", total == 0 ? "UNSYNCED"
                : longValue(row.get("knownAmountOrders")) == total ? "FULL" : "PARTIAL");
        result.put("message", total == 0
                ? "当前缓存没有可验证订单；不把缺失数据显示为 0 笔成交"
                : "金额仅汇总有同步证据的订单");
        return result;
    }

    public List<Map<String, Object>> events(Long accountId, String goodsId, Integer limit) {
        requireProductAccess(accountId, goodsId);
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query("""
                SELECT id, event_type, event_origin, outcome_state, data_source, operator_user_id,
                       operator_username, request_id, idempotency_key, batch_job_id, batch_item_id,
                       platform_request_id, before_json, after_json,
                       field_diff_json, raw_snapshot_hash, platform_response_code, error_message, created_time
                  FROM xianyu_goods_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                 ORDER BY created_time DESC, id DESC LIMIT ?
                """, (rs, rowNum) -> eventRow(rs), requireTenant(), accountId, goodsId, safeLimit);
    }

    @Transactional
    public Map<String,Object> saveFilter(SavedFilterCommand command) {
        if(command==null) throw new BusinessException(400,"筛选器参数不能为空");
        String requestId=requireText(command.requestId(),"requestId",80);
        String name=requireText(command.name(),"筛选器名称",100);
        ProductFilter filter=normalizeFilter(command.filter());
        List<Map<String,Object>> replay=jdbcTemplate.queryForList(
                "SELECT id,filter_name name,filter_json filterJson,updated_time updatedTime FROM xianyu_saved_filter WHERE tenant_id=? AND request_id=?",
                requireTenant(),requestId);
        if(!replay.isEmpty()){Map<String,Object> result=new LinkedHashMap<>(replay.getFirst());result.put("filter",readJson((String)result.remove("filterJson")));result.put("idempotentReplay",true);return result;}
        jdbcTemplate.update("""
                INSERT INTO xianyu_saved_filter(tenant_id,user_id,filter_type,filter_name,filter_json,request_id)
                VALUES (?,?,'PRODUCT',?,?,?)
                ON DUPLICATE KEY UPDATE filter_json=VALUES(filter_json),request_id=VALUES(request_id)
                """,requireTenant(),UserContext.getUserId(),name,json(filter),requestId);
        Map<String,Object> result=listFilters().stream().filter(item->name.equals(item.get("name"))).findFirst().orElseThrow();
        result=new LinkedHashMap<>(result);result.put("idempotentReplay",false);
        audit(null,"PRODUCT_FILTER_SAVE","保存商品筛选器",requestId,"LOCAL_SUCCESS",command,result);
        return result;
    }

    public List<Map<String,Object>> listFilters(){
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("""
                SELECT id,filter_name name,filter_json filterJson,updated_time updatedTime
                  FROM xianyu_saved_filter WHERE tenant_id=? AND user_id=? AND filter_type='PRODUCT'
                 ORDER BY updated_time DESC,id DESC
                """,requireTenant(),UserContext.getUserId());
        rows.forEach(row->row.put("filter",readJson((String)row.remove("filterJson"))));return rows;
    }

    @Transactional
    public void deleteFilter(Long id,String requestId){
        requireText(requestId,"requestId",80);
        int deleted=jdbcTemplate.update("DELETE FROM xianyu_saved_filter WHERE tenant_id=? AND user_id=? AND id=?",
                requireTenant(),UserContext.getUserId(),id);
        if(deleted==0)throw new BusinessException(404,"筛选器不存在");
        audit(null,"PRODUCT_FILTER_DELETE","删除商品筛选器",requestId,"LOCAL_SUCCESS",Map.of("id",id),null);
    }

    public String exportProducts(ProductFilter value,String requestId){
        requireText(requestId,"requestId",80);ProductFilter filter=normalizeFilter(value);QueryParts query=queryParts(filter,true);
        List<Map<String,Object>> products=namedJdbc.query("SELECT goods.*,account.account_note,account.unb "+query.fromWhere()
                +" ORDER BY goods.updated_time DESC,goods.id DESC LIMIT 10001",query.params(),(rs,row)->productRow(rs));
        if(products.size()>10000)throw new BusinessException(400,"单次最多导出10000个商品，请缩小筛选范围");
        Map<String,Map<String,Object>> metrics=exportMetrics(query,filter.metricWindowDays());
        StringBuilder csv=new StringBuilder("\uFEFF店铺,店铺ID,商品ID,标题,价格,库存,状态,来源,发布通道,同步状态,商品覆盖度,最后同步,经营窗口,曝光人数,详情访客,咨询买家,支付买家,完成买家,退款买家,支付订单,支付金额,退款订单,退款金额,样本天数,指标来源,指标覆盖度,指标日期,指标同步时间\r\n");
        for(Map<String,Object> item:products){
            Map<String,Object> metric=metrics.getOrDefault(productKey(item.get("accountId"),item.get("goodsId")),Map.of());
            csv.append(csv(item.get("accountNote"))).append(',').append(csv(item.get("accountId"))).append(',')
                    .append(csv(item.get("goodsId"))).append(',').append(csv(item.get("title"))).append(',').append(csv(item.get("price"))).append(',')
                    .append(csv(item.get("stock"))).append(',').append(csv(item.get("status"))).append(',').append(csv(item.get("source"))).append(',')
                    .append(csv(item.get("publishChannel"))).append(',').append(csv(item.get("syncStatus"))).append(',')
                    .append(csv(item.get("coverageStatus"))).append(',').append(csv(item.get("lastSyncedTime"))).append(',')
                    .append(csv(filter.metricWindowDays())).append(',')
                    .append(csv(exportMetricValue(metric,"exposureUvCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"visitorCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"inquiryBuyerCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"paidBuyerCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"completedBuyerCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"refundBuyerCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"paidOrderCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"paidAmount"))).append(',')
                    .append(csv(exportMetricValue(metric,"refundOrderCount"))).append(',')
                    .append(csv(exportMetricValue(metric,"refundAmount"))).append(',')
                    .append(csv(metric.get("sampleDays"))).append(',')
                    .append(csv(integer(metric.get("sourceCount"))!=null&&integer(metric.get("sourceCount"))>1?"MULTIPLE":metric.get("sources"))).append(',')
                    .append(csv(metric.getOrDefault("metricCoverageStatus","UNSYNCED"))).append(',')
                    .append(csv(metric.get("dataDate"))).append(',').append(csv(metric.get("syncedAt"))).append("\r\n");
        }
        auditProductExport(products, filter, requestId);return csv.toString();
    }

    /** 跨店导出按涉及店铺逐条留痕，使现有账号范围审计可检索且不会形成 accountId=null 孤立记录。 */
    private void auditProductExport(List<Map<String, Object>> products, ProductFilter filter, String requestId) {
        Set<Long> accounts = new LinkedHashSet<>();
        for (Map<String, Object> product : products) {
            Long accountId = longObject(product.get("accountId"));
            if (accountId != null) accounts.add(accountId);
        }
        if (accounts.isEmpty() && filter.accountIds() != null) {
            accounts.addAll(filter.accountIds());
        }
        if (accounts.isEmpty()) {
            AccountScopeContext.Scope scope = AccountScopeContext.get();
            if (scope != null && !scope.unrestricted()) {
                accounts.addAll(scope.accountIds());
            } else {
                jdbcTemplate.queryForList("SELECT id FROM xianyu_account WHERE tenant_id=? ORDER BY id", requireTenant())
                        .forEach(row -> {
                            Long accountId = longObject(row.get("id"));
                            if (accountId != null) accounts.add(accountId);
                        });
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportedCount", products.size());
        result.put("metricWindowDays", filter.metricWindowDays());
        result.put("unknownMetricValuesRemainBlank", true);
        result.put("accountScope", accounts);
        result.put("accountScopeCount", accounts.size());
        for (Long accountId : accounts) {
            audit(accountId,"PRODUCT_EXPORT","导出商品经营报表",requestId,"LOCAL_SUCCESS",filter,result);
        }
    }

    private Map<String, Map<String, Object>> exportMetrics(QueryParts query, int metricWindowDays) {
        MapSqlParameterSource params=copy(query.params()).addValue("metricDays",Math.max(0,metricWindowDays-1))
                .addValue("metricWindowDays",metricWindowDays);
        List<Map<String,Object>> rows=namedJdbc.queryForList("""
                SELECT filtered.accountId,filtered.goodsId,COUNT(metric.id) sampleRows,
                       COUNT(DISTINCT metric.metric_date) sampleDays,COUNT(DISTINCT metric.source) sourceCount,
                       GROUP_CONCAT(DISTINCT metric.source ORDER BY metric.source SEPARATOR ',') sources,
                       MAX(metric.metric_date) dataDate,MAX(metric.synced_at) syncedAt,
                       SUM(metric.exposure_uv_count) exposureUvCount,SUM(metric.visitor_count) visitorCount,
                       SUM(metric.inquiry_buyer_count) inquiryBuyerCount,SUM(metric.paid_buyer_count) paidBuyerCount,
                       SUM(metric.completed_buyer_count) completedBuyerCount,SUM(metric.refund_buyer_count) refundBuyerCount,
                       SUM(metric.paid_order_count) paidOrderCount,SUM(metric.paid_amount) paidAmount,
                       SUM(metric.refund_order_count) refundOrderCount,SUM(metric.refund_amount) refundAmount,
                       CASE WHEN COUNT(metric.id)=0 THEN 'UNSYNCED'
                            WHEN COUNT(DISTINCT metric.metric_date)>=:metricWindowDays
                             AND COUNT(DISTINCT metric.source)=1
                             AND SUM(metric.coverage_status='FULL')=COUNT(metric.id) THEN 'FULL'
                            ELSE 'PARTIAL' END metricCoverageStatus
                  FROM (SELECT goods.tenant_id tenantId,goods.xianyu_account_id accountId,goods.xy_good_id goodsId
                """+query.fromWhere()+"""
                       ) filtered
                  LEFT JOIN xianyu_goods_metric_daily metric
                    ON metric.tenant_id=filtered.tenantId AND metric.xianyu_account_id=filtered.accountId
                   AND metric.xy_goods_id=filtered.goodsId
                   AND metric.metric_date>=DATE_SUB(CURRENT_DATE(),INTERVAL :metricDays DAY)
                 GROUP BY filtered.tenantId,filtered.accountId,filtered.goodsId
                """,params);
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Map<String,Object> row:rows)result.put(productKey(row.get("accountId"),row.get("goodsId")),row);
        return result;
    }

    private static String productKey(Object accountId,Object goodsId){return String.valueOf(accountId)+":"+String.valueOf(goodsId);}

    static Object exportMetricValue(Map<String,Object> metric,String field){
        Integer sourceCount=integer(metric.get("sourceCount"));
        return sourceCount!=null&&sourceCount==1?metric.get(field):null;
    }

    public BatchPreview previewBatch(BatchRequest request) {
        BatchRequest normalized = normalizeBatchRequest(request, false);
        List<ProductRef> selected = resolveSelection(normalized);
        Long tenantId = requireTenant();
        List<BatchCandidate> candidates = new ArrayList<>();
        int conflicts = 0;
        int qaMockCount = 0;
        Set<Long> accounts = new LinkedHashSet<>();
        for (ProductRef ref : selected) {
            Map<String, Object> product = findProduct(ref.accountId(), ref.goodsId());
            boolean qaMock = productBatchQaMockService.isEligible(tenantId, ref.accountId(), ref.goodsId());
            String conflict = qaMock ? null : conflict(normalized.operationType(), normalized.operationParams(), product);
            if (qaMock) qaMockCount++;
            if (conflict != null) conflicts++;
            accounts.add(ref.accountId());
            candidates.add(new BatchCandidate(ref.accountId(), ref.goodsId(), string(product.get("title")),
                    integer(product.get("status")), longValue(product.get("row_version")),
                    oldValue(product), conflict == null, conflict));
        }
        if (qaMockCount > 0 && qaMockCount < selected.size()) {
            throw new BusinessException(400, "隔离 QA 商品不能与平台商品混合创建任务");
        }
        int executable = selected.size() - conflicts;
        String executionChannel = executable > 0 && qaMockCount == selected.size() ? "QA_MOCK" : "PLATFORM";
        String previewToken = sha256(normalized.operationType() + "|" + json(normalized.operationParams()) + "|"
                + candidates.stream().map(item -> item.accountId() + ":" + item.goodsId() + ":" + item.status()
                + ":" + item.executable()).toList());
        String confirmation = "确认对" + accounts.size() + "个店铺的" + executable + "个商品执行"
                + operationLabel(normalized.operationType()) + "，冲突" + conflicts + "个"
                + ("DELETE".equals(normalized.operationType()) ? "；删除不可恢复" : "")
                + ("QA_MOCK".equals(executionChannel) ? "；隔离 QA Mock，不触达平台" : "");
        return new BatchPreview(normalized.operationType(), normalized.selectionMode(), selected.size(),
                accounts.size(), conflicts, executable, confirmation, previewToken, List.copyOf(candidates),
                executionChannel, "QA_MOCK".equals(executionChannel) ? "隔离测试通道：所有执行结果均由本地持久化状态机产生，不发起平台网络请求。" : null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createBatch(BatchRequest request) {
        BatchRequest normalized = normalizeBatchRequest(request, true);
        Long tenantId = requireTenant();
        List<Map<String, Object>> replay = jdbcTemplate.queryForList(
                "SELECT id, batch_id FROM xianyu_goods_batch_job WHERE tenant_id=? AND idempotency_key=?",
                tenantId, normalized.idempotencyKey());
        if (!replay.isEmpty()) {
            Long jobId = ((Number) replay.getFirst().get("id")).longValue();
            Map<String, Object> existing = batchDetail(jobId);
            existing.put("idempotentReplay", true);
            return existing;
        }
        BatchPreview preview = previewBatch(normalized);
        if (!preview.confirmationSummary().equals(normalized.confirmationText())) {
            throw new BusinessException(400, "确认范围已变化，请重新预检并使用最新确认文案");
        }
        if (!preview.previewToken().equals(normalized.previewToken())) {
            throw new BusinessException(409, "商品范围、版本或能力已变化，请重新预检");
        }
        if (preview.executableCount() == 0) throw new BusinessException(409, "所选商品全部存在冲突，无法创建任务");
        String batchId = "PB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        try {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_goods_batch_job
                    (tenant_id, batch_id, request_id, idempotency_key, operation_type, selection_mode, selection_query_json,
                     filter_snapshot_hash,
                     operation_params_json, execution_channel, status, selected_count, conflict_count, executable_count,
                     max_operations_per_minute, confirmation_summary, operator_user_id, operator_username)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?,?,?)
                    """, tenantId, batchId, normalized.requestId(), normalized.idempotencyKey(), normalized.operationType(), normalized.selectionMode(),
                    json(selectionSnapshot(normalized)),
                    preview.previewToken(), json(normalized.operationParams()), preview.executionChannel(), preview.selectedCount(), preview.conflictCount(),
                    preview.executableCount(), normalized.maxOperationsPerMinute(), preview.confirmationSummary(),
                    UserContext.getUserId(), UserContext.getUsername());
        } catch (DuplicateKeyException concurrentCreate) {
            List<Map<String, Object>> winner = jdbcTemplate.queryForList(
                    "SELECT id, batch_id FROM xianyu_goods_batch_job WHERE tenant_id=? AND idempotency_key=?",
                    tenantId, normalized.idempotencyKey());
            if (winner.isEmpty()) throw concurrentCreate;
            Long winnerJobId = ((Number) winner.getFirst().get("id")).longValue();
            Map<String, Object> existing = batchDetail(winnerJobId);
            existing.put("idempotentReplay", true);
            return existing;
        }
        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM xianyu_goods_batch_job WHERE tenant_id=? AND batch_id=?", Long.class, tenantId, batchId);
        if (jobId == null) throw new BusinessException(500, "批量任务创建后无法读取");
        for (BatchCandidate item : preview.items()) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_goods_batch_item
                    (tenant_id, batch_job_id, batch_id, xianyu_account_id, xy_goods_id, operation_type,
                     expected_goods_version, old_value_json, new_value_json,
                     status, conflict_code, conflict_message, outcome_state)
                    VALUES (?,?,?,?,?,?,?,?,?,?, ?,?, 'PENDING')
                    """, tenantId, jobId, batchId, item.accountId(), item.goodsId(), normalized.operationType(),
                    item.rowVersion(), json(item.oldValue()), json(normalized.operationParams()),
                    item.executable() ? "QUEUED" : "CONFLICT", item.executable() ? null : "PRECHECK_CONFLICT",
                    item.conflictMessage());
        }
        audit(null, "PRODUCT_BATCH_CREATE", "创建商品批量任务", normalized.requestId(),
                "LOCAL_SUCCESS", normalized, Map.of("batchId", batchId, "scope", preview.confirmationSummary()));
        Map<String, Object> result = batchDetail(jobId);
        result.put("idempotentReplay", false);
        return result;
    }

    public List<Map<String, Object>> batches(String status, Integer limit) {
        String normalizedStatus = normalizeOptional(status, BATCH_STATUSES, "批量任务状态");
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        String scope = itemScopeCondition("item");
        String sql = """
                SELECT job.* FROM xianyu_goods_batch_job job
                 WHERE job.tenant_id=?
                   AND EXISTS (SELECT 1 FROM xianyu_goods_batch_item item
                                WHERE item.tenant_id=job.tenant_id AND item.batch_job_id=job.id
                """ + scope + ")" + (normalizedStatus == null ? "" : " AND job.status=?")
                + " ORDER BY job.created_time DESC, job.id DESC LIMIT ?";
        Object[] args = normalizedStatus == null
                ? new Object[]{requireTenant(), safeLimit}
                : new Object[]{requireTenant(), normalizedStatus, safeLimit};
        List<Map<String, Object>> jobs = jdbcTemplate.query(sql, (rs, rowNum) -> batchRow(rs), args);
        jobs.forEach(this::redactJobToVisibleScope);
        return jobs;
    }

    public List<Map<String, Object>> batches(BatchQuery query) {
        BatchQuery q = query == null ? new BatchQuery(null, null, null, null, null, null, null, 50) : query;
        String status = normalizeOptional(q.status(), BATCH_STATUSES, "批量任务状态");
        int limit = q.limit() == null ? 50 : Math.max(1, Math.min(q.limit(), 200));
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", requireTenant()).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT DISTINCT job.* FROM xianyu_goods_batch_job job JOIN xianyu_goods_batch_item item")
                .append(" ON item.tenant_id=job.tenant_id AND item.batch_job_id=job.id WHERE job.tenant_id=:tenantId")
                .append(itemScopeCondition("item"));
        if (status != null) { sql.append(" AND job.status=:status"); params.addValue("status", status); }
        if (trim(q.operationType()) != null) { sql.append(" AND job.operation_type=:operation"); params.addValue("operation", upper(q.operationType())); }
        if (q.accountId() != null) { accountAccessService.requireAccess(q.accountId()); sql.append(" AND item.xianyu_account_id=:accountId"); params.addValue("accountId", q.accountId()); }
        if (q.operatorUserId() != null) { sql.append(" AND job.operator_user_id=:operatorId"); params.addValue("operatorId", q.operatorUserId()); }
        if (trim(q.search()) != null) { sql.append(" AND (job.batch_id LIKE :search OR job.request_id LIKE :search)"); params.addValue("search", "%" + trim(q.search()) + "%"); }
        if (trim(q.createdFrom()) != null) { sql.append(" AND job.created_time>=:createdFrom"); params.addValue("createdFrom", q.createdFrom()); }
        if (trim(q.createdTo()) != null) { sql.append(" AND job.created_time<=:createdTo"); params.addValue("createdTo", q.createdTo()); }
        sql.append(" ORDER BY job.created_time DESC,job.id DESC LIMIT :limit");
        List<Map<String, Object>> jobs = namedJdbc.query(sql.toString(), params, (rs, rowNum) -> batchRow(rs));
        jobs.forEach(this::redactJobToVisibleScope);
        return jobs;
    }

    public Map<String, Object> batchDetail(Long jobId) {
        if (jobId == null || jobId <= 0) throw new BusinessException(400, "批量任务ID无效");
        String scope = itemScopeCondition("item");
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT job.* FROM xianyu_goods_batch_job job
                 WHERE job.tenant_id=? AND job.id=?
                   AND EXISTS (SELECT 1 FROM xianyu_goods_batch_item item
                                WHERE item.tenant_id=job.tenant_id AND item.batch_job_id=job.id
                """ + scope + ")", (rs, rowNum) -> batchRow(rs), requireTenant(), jobId);
        if (jobs.isEmpty()) throw new BusinessException(404, "批量任务不存在或不在当前账号权限范围");
        Map<String, Object> result = jobs.getFirst();
        List<Map<String, Object>> items = jdbcTemplate.query("""
                SELECT item.*, goods.title, account.account_note
                  FROM xianyu_goods_batch_item item
                  LEFT JOIN xianyu_goods goods ON goods.tenant_id=item.tenant_id
                    AND goods.xianyu_account_id=item.xianyu_account_id AND goods.xy_good_id=item.xy_goods_id
                  JOIN xianyu_account account ON account.id=item.xianyu_account_id AND account.tenant_id=item.tenant_id
                 WHERE item.tenant_id=? AND item.batch_job_id=?
                """ + itemScopeCondition("item") + " ORDER BY item.id", (rs, rowNum) -> batchItemRow(rs),
                requireTenant(), jobId);
        result.put("items", items);
        redactJobToVisibleItems(result, items);
        int rate = result.get("maxOperationsPerMinute") instanceof Number n ? Math.max(1, n.intValue()) : 10;
        long remaining = items.stream().filter(item -> Set.of("QUEUED", "RUNNING").contains(item.get("status"))).count();
        result.put("estimatedWaitSeconds", remaining == 0 ? 0 : (long) Math.ceil(remaining * 60d / rate));
        result.put("unknownRequiresManualReview", items.stream().anyMatch(item -> "UNKNOWN".equals(item.get("status"))));
        return result;
    }

    /** 仅供 qa profile 的隔离控制器记录故障注入/人工调度证据。 */
    public void recordQaControl(Long jobId, String action, String requestId, Map<String, Object> result) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> batch = batchDetail(jobId);
        if (!"QA_MOCK".equals(batch.get("executionChannel"))) {
            throw new BusinessException(404, "任务不是隔离 QA Mock 任务");
        }
        audit(null, "PRODUCT_BATCH_QA_" + upper(action), "隔离 QA 批任务控制", requestId,
                "LOCAL_SUCCESS", Map.of("jobId", jobId, "action", action), result);
    }

    private void redactJobToVisibleScope(Map<String, Object> job) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return;
        Long jobId = job.get("jobId") instanceof Number number ? number.longValue() : null;
        if (jobId == null) return;
        List<Map<String, Object>> visibleItems = jdbcTemplate.queryForList("""
                SELECT status,xianyu_account_id accountId FROM xianyu_goods_batch_item item
                 WHERE item.tenant_id=? AND item.batch_job_id=?
                """ + itemScopeCondition("item"), requireTenant(), jobId);
        redactJobToVisibleItems(job, visibleItems);
    }

    void redactJobToVisibleItems(Map<String, Object> job, List<Map<String, Object>> visibleItems) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return;
        Map<String, Long> counts = visibleItems.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> string(item.get("status")), java.util.stream.Collectors.counting()));
        long success = counts.getOrDefault("SUCCEEDED", 0L);
        long failed = counts.getOrDefault("FAILED", 0L);
        long unknown = counts.getOrDefault("UNKNOWN", 0L);
        long skipped = counts.getOrDefault("SKIPPED", 0L);
        long cancelled = counts.getOrDefault("CANCELLED", 0L);
        long conflicts = counts.getOrDefault("CONFLICT", 0L);
        long active = counts.getOrDefault("QUEUED", 0L) + counts.getOrDefault("RUNNING", 0L);
        long total = visibleItems.size();
        long accountCount = visibleItems.stream().map(item -> item.get("accountId"))
                .filter(java.util.Objects::nonNull).distinct().count();
        job.put("selectedCount", total);
        job.put("accountCount", accountCount);
        job.put("executableCount", Math.max(0, total - conflicts));
        job.put("conflictCount", conflicts);
        job.put("successCount", success);
        job.put("failedCount", failed);
        job.put("unknownCount", unknown);
        job.put("skippedCount", skipped);
        job.put("cancelledCount", cancelled);
        job.put("progressPercent", total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(
                (success + failed + unknown + skipped + cancelled + conflicts) * 100d / total).setScale(2, RoundingMode.HALF_UP));
        int rate = job.get("maxOperationsPerMinute") instanceof Number number ? Math.max(1, number.intValue()) : 10;
        job.put("estimatedWaitSecondsUpperBound", active == 0 ? 0 : (long) Math.ceil(active * 60d / rate));
        job.put("visibleScopeOnly", true);
        String visibleStatus = visibleBatchStatus(string(job.get("status")), total, active, success, failed,
                unknown, skipped, cancelled, conflicts);
        job.put("status", visibleStatus);
        String action = switch (string(job.get("operationType"))) {
            case "SYNC" -> "同步";
            case "ON_SALE" -> "上架";
            case "OFF_SHELF" -> "下架";
            case "POLISH" -> "擦亮";
            case "CHANGE_PRICE" -> "改价";
            case "CHANGE_STOCK" -> "改库存";
            case "DELETE" -> "删除";
            default -> "批量操作";
        };
        job.put("confirmationSummary", "当前权限范围内：" + accountCount + "个店铺、" + total + "个商品，操作：" + action);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("event", "VISIBLE_SCOPE_TERMINAL");
        evidence.put("jobId", job.get("jobId"));
        evidence.put("status", visibleStatus);
        evidence.put("successCount", success);
        evidence.put("failedCount", failed);
        evidence.put("unknownCount", unknown);
        evidence.put("skippedCount", skipped);
        evidence.put("cancelledCount", cancelled);
        evidence.put("visibleScopeOnly", true);
        evidence.put("externalDispatched", null);
        job.put("notificationEvidence", evidence);
    }

    static String visibleBatchStatus(String storedStatus, long total, long active, long success, long failed,
                                     long unknown, long skipped, long cancelled, long conflicts) {
        if (active > 0) return "CANCEL_REQUESTED".equals(storedStatus) ? "CANCEL_REQUESTED" : "RUNNING";
        if (total <= 0) return "UNKNOWN";
        if (success == total) return "SUCCEEDED";
        if (failed == total) return "FAILED";
        if (unknown == total) return "UNKNOWN";
        if (skipped == total) return "SKIPPED";
        if (cancelled == total) return "CANCELLED";
        if (conflicts == total) return "CONFLICT";
        return "PARTIAL_SUCCESS";
    }

    @Transactional
    public Map<String, Object> retryBatchFailures(Long jobId, String requestId) {
        return retryBatchFailures(jobId, requestId, null);
    }

    @Transactional
    public Map<String, Object> retryBatchFailures(Long jobId, String requestId, List<Long> selectedItemIds) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> batch = batchDetail(jobId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) batch.get("items");
        int retried = 0;
        for (Map<String, Object> item : items) {
            String status = string(item.get("status"));
            if (!ITEM_RETRYABLE.contains(status)) continue;
            Long itemId = ((Number) item.get("itemId")).longValue();
            if (selectedItemIds != null && !selectedItemIds.contains(itemId)) continue;
            Long accountId = ((Number) item.get("accountId")).longValue();
            String goodsId = string(item.get("goodsId"));
            int inserted = jdbcTemplate.update("""
                    INSERT IGNORE INTO xianyu_goods_event
                    (tenant_id, xianyu_account_id, xy_goods_id, event_type, event_origin, outcome_state,
                     data_source, operator_user_id, operator_username, request_id)
                    VALUES (?,?,?,'BATCH_RETRY','USER','LOCAL_SUCCESS','LOCAL',?,?,?)
                    """, requireTenant(), accountId, goodsId, UserContext.getUserId(), UserContext.getUsername(), requestId);
            if (inserted == 0) continue;
            retried += jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_item
                       SET status='QUEUED', next_retry_time=NULL,
                           error_code=NULL, error_message=NULL, result_json=NULL,
                           platform_request_id=NULL, claimed_by=NULL, claimed_time=NULL,
                           started_time=NULL, outcome_state='QUEUED', completed_time=NULL
                     WHERE tenant_id=? AND id=? AND status='FAILED'
                    """, requireTenant(), itemId);
        }
        if (retried > 0) {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_batch_job
                       SET status='QUEUED', completed_time=NULL,
                           notification_sent=0, notification_evidence_json=NULL
                     WHERE tenant_id=? AND id=?
                    """,
                    requireTenant(), jobId);
        }
        audit(null, "PRODUCT_BATCH_RETRY", "重试商品批量任务失败项", requestId,
                "LOCAL_SUCCESS", Map.of("jobId", jobId), Map.of("retriedCount", retried));
        Map<String, Object> result = batchDetail(jobId);
        result.put("retriedCount", retried);
        result.put("idempotentReplay", retried == 0);
        return result;
    }

    @Transactional
    public Map<String, Object> cancelBatch(Long jobId, String requestId, String reason) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> before = batchDetail(jobId);
        String status = string(before.get("status"));
        if (Set.of("SUCCEEDED", "FAILED", "PARTIAL_SUCCESS", "CANCELLED").contains(status)) {
            Map<String, Object> replay = new LinkedHashMap<>(before);
            replay.put("idempotentReplay", true);
            return replay;
        }
        int event = jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_goods_event
                (tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,data_source,
                 operator_user_id,operator_username,request_id,idempotency_key,batch_job_id,error_message)
                SELECT item.tenant_id,item.xianyu_account_id,item.xy_goods_id,'BATCH_CANCEL','USER','LOCAL_SUCCESS','LOCAL',
                       ?,?,?,?,item.batch_job_id,?
                  FROM xianyu_goods_batch_item item
                 WHERE item.tenant_id=? AND item.batch_job_id=? LIMIT 1
                """, UserContext.getUserId(), UserContext.getUsername(), requestId, requestId, limit(reason, 500), requireTenant(), jobId);
        if (event == 0) {
            Map<String, Object> replay = batchDetail(jobId);
            replay.put("idempotentReplay", true);
            return replay;
        }
        jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_job SET status='CANCEL_REQUESTED',cancel_requested_time=NOW(3),cancellation_reason=?
                 WHERE tenant_id=? AND id=? AND status IN ('PENDING_CONFIRMATION','QUEUED','RUNNING','CANCEL_REQUESTED')
                """, limit(reason, 500), requireTenant(), jobId);
        jdbcTemplate.update("""
                UPDATE xianyu_goods_batch_item SET status='CANCELLED',outcome_state='NOT_EXECUTED',
                       cancelled_time=NOW(3),completed_time=NOW(3)
                 WHERE tenant_id=? AND batch_job_id=? AND status='QUEUED'
                """, requireTenant(), jobId);
        audit(null, "PRODUCT_BATCH_CANCEL", "取消商品批量任务", requestId, "LOCAL_SUCCESS",
                Map.of("jobId", jobId, "reason", reason == null ? "" : reason), null);
        Map<String, Object> result = batchDetail(jobId);
        result.put("idempotentReplay", false);
        return result;
    }

    public String exportBatchFailures(Long jobId, String requestId) {
        requireText(requestId, "requestId", 80);
        Map<String, Object> batch = batchDetail(jobId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) batch.get("items");
        StringBuilder csv = new StringBuilder("\uFEFF批次,店铺,账号ID,商品ID,标题,状态,结果层,冲突,错误,平台响应码\r\n");
        int count = 0;
        for (Map<String, Object> item : items) {
            if (!Set.of("FAILED", "UNKNOWN", "CONFLICT").contains(item.get("status"))) continue;
            count++;
            csv.append(csv(batch.get("batchId"))).append(',').append(csv(item.get("accountNote"))).append(',')
                    .append(csv(item.get("accountId"))).append(',').append(csv(item.get("goodsId"))).append(',')
                    .append(csv(item.get("title"))).append(',').append(csv(item.get("status"))).append(',')
                    .append(csv(item.get("outcomeState"))).append(',').append(csv(item.get("conflictMessage"))).append(',')
                    .append(csv(item.get("errorMessage"))).append(',').append(csv(item.get("platformResponseCode"))).append("\r\n");
        }
        audit(null, "PRODUCT_BATCH_EXPORT", "导出商品批量任务失败明细", requestId,
                "LOCAL_SUCCESS", Map.of("jobId", jobId), Map.of("exportedCount", count));
        return csv.toString();
    }

    private ProductFilter normalizeFilter(ProductFilter value) {
        ProductFilter filter = value == null ? new ProductFilter(null, null, null, null, null, null, 7, 1, 20) : value;
        String bucket = filter.statusBucket() == null ? "ALL" : requireEnum(filter.statusBucket(), STATUS_BUCKETS, "商品状态");
        int page = filter.page() == null || filter.page() < 1 ? 1 : filter.page();
        int size = filter.pageSize() == null || filter.pageSize() < 1 ? 20 : Math.min(filter.pageSize(), 100);
        List<Long> accountIds = filter.accountIds() == null ? List.of() : filter.accountIds().stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        accountIds.forEach(accountAccessService::requireAccess);
        int window = filter.metricWindowDays() == null ? 7 : filter.metricWindowDays();
        if (!Set.of(1, 7, 30).contains(window)) throw new BusinessException(400, "数据窗口仅支持1、7、30天");
        if (filter.groupId() != null && filter.groupId() <= 0) throw new BusinessException(400, "店铺分组无效");
        return new ProductFilter(trim(filter.search()), accountIds, filter.groupId(), bucket, upper(filter.source()),
                upper(filter.publishChannel()), window, page, size);
    }

    private QueryParts queryParts(ProductFilter filter, boolean includeStatus) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", requireTenant());
        StringBuilder where = new StringBuilder(" FROM xianyu_goods goods JOIN xianyu_account account")
                .append(" ON account.id=goods.xianyu_account_id AND account.tenant_id=goods.tenant_id")
                .append(" WHERE goods.tenant_id=:tenantId");
        appendAccountScope(where, params, "goods", filter.accountIds());
        if (filter.groupId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM xianyu_account_group_member gm WHERE gm.tenant_id=goods.tenant_id")
                    .append(" AND gm.xianyu_account_id=goods.xianyu_account_id AND gm.group_id=:groupId)");
            params.addValue("groupId", filter.groupId());
        }
        if (filter.search() != null) {
            where.append(" AND (goods.xy_good_id LIKE :search OR goods.title LIKE :search OR goods.outer_id LIKE :search)");
            params.addValue("search", "%" + filter.search() + "%");
            params.addValue("exactSearch", filter.search());
        }
        if (filter.source() != null) {
            where.append(" AND goods.product_source=:source");
            params.addValue("source", filter.source());
        }
        if (filter.publishChannel() != null) {
            where.append(" AND goods.publish_channel=:channel");
            params.addValue("channel", filter.publishChannel());
        }
        if (includeStatus && !"ALL".equals(filter.statusBucket())) where.append(" AND ").append(statusPredicate(filter.statusBucket()));
        return new QueryParts(where.toString(), params);
    }

    private void appendAccountScope(StringBuilder where, MapSqlParameterSource params, String alias, List<Long> requested) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        Set<Long> allowed = scope == null || scope.unrestricted() ? null : scope.accountIds();
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        if (requested != null && !requested.isEmpty()) effective.addAll(requested);
        if (allowed != null) {
            if (effective.isEmpty()) effective.addAll(allowed);
            else effective.retainAll(allowed);
        }
        if ((allowed != null || requested != null && !requested.isEmpty()) && effective.isEmpty()) {
            where.append(" AND 1=0");
        } else if (!effective.isEmpty()) {
            where.append(" AND ").append(alias).append(".xianyu_account_id IN (:accountIds)");
            params.addValue("accountIds", effective);
        }
    }

    private String statusPredicate(String bucket) {
        return switch (bucket) {
            case "ALL" -> "";
            case "ON_SALE" -> "goods.status=0 AND goods.product_source<>'LOCAL_DRAFT'";
            case "SOLD" -> "goods.status=2 AND goods.product_source<>'LOCAL_DRAFT'";
            case "OFF_SHELF" -> "goods.status IN (1,-1,-98) AND goods.product_source<>'LOCAL_DRAFT'";
            case "DRAFT" -> "goods.product_source='LOCAL_DRAFT'";
            default -> "goods.status NOT IN (0,1,2,-1,-98) AND goods.product_source<>'LOCAL_DRAFT'";
        };
    }

    private Map<String, Object> productRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getLong("id"));
        map.put("goodsId", rs.getString("xy_good_id"));
        map.put("outerId", rs.getString("outer_id"));
        map.put("accountId", nullableLong(rs, "xianyu_account_id"));
        map.put("accountNote", rs.getString("account_note"));
        map.put("accountUnb", rs.getString("unb"));
        map.put("title", rs.getString("title"));
        map.put("coverPic", rs.getString("cover_pic"));
        map.put("galleryJson", rs.getString("info_pic"));
        map.put("detailJson", rs.getString("detail_info"));
        map.put("detailUrl", rs.getString("detail_url"));
        map.put("price", decimal(rs.getString("sold_price")));
        map.put("originalPrice", rs.getBigDecimal("original_price"));
        map.put("shippingFee", rs.getBigDecimal("shipping_fee"));
        map.put("shippingType", rs.getString("shipping_type"));
        map.put("skuCount", nullableInteger(rs, "sku_count"));
        map.put("stock", nullableInteger(rs, "stock"));
        int status = rs.getInt("status");
        map.put("status", rs.wasNull() ? null : status);
        map.put("statusBucket", statusBucket(status, rs.getString("product_source")));
        map.put("source", rs.getString("product_source"));
        map.put("publishChannel", rs.getString("publish_channel"));
        map.put("itemType", rs.getString("item_type"));
        map.put("categoryId", rs.getString("category_id"));
        map.put("categoryName", rs.getString("category_name"));
        map.put("businessMode", rs.getString("business_mode"));
        map.put("conditionCode", rs.getString("condition_code"));
        map.put("supportPolicy", rs.getString("support_policy"));
        map.put("location", rs.getString("location_text"));
        map.put("syncStatus", rs.getString("sync_status"));
        map.put("coverageStatus", rs.getString("coverage_status"));
        map.put("rowVersion", rs.getLong("row_version"));
        map.put("createdTime", instant(rs, "created_time"));
        map.put("updatedTime", instant(rs, "updated_time"));
        map.put("platformUpdatedTime", instant(rs, "platform_updated_time"));
        map.put("lastSyncedTime", instant(rs, "last_synced_time"));
        map.put("lastSyncRequestId", rs.getString("last_sync_request_id"));
        map.put("lastSyncErrorCode", rs.getString("last_sync_error_code"));
        map.put("lastSyncErrorMessage", rs.getString("last_sync_error_message"));
        return map;
    }

    private void enrichWarehouseEvidence(Map<String, Object> product) {
        Long accountId = product.get("accountId") instanceof Number n ? n.longValue() : null;
        String goodsId = string(product.get("goodsId"));
        if (accountId == null || goodsId.isBlank()) return;
        Map<String, Object> evidence = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) mappingCount,
                       SUM(CASE WHEN delivery_mode=2 THEN 1 ELSE 0 END) cardPoolMappingCount,
                       MAX(update_time) mappingSyncedAt
                  FROM xianyu_goods_auto_delivery_config
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, requireTenant(), accountId, goodsId);
        long mappings = evidence.get("mappingCount") instanceof Number n ? n.longValue() : 0;
        product.put("warehouseStatus", mappings == 0 ? "UNCONFIGURED" : "CONFIGURED");
        product.put("fulfillmentMappingCount", mappings);
        product.put("cardPoolMappingCount", evidence.get("cardPoolMappingCount"));
        product.put("fulfillmentSyncedAt", evidence.get("mappingSyncedAt"));
    }

    private List<Map<String, Object>> skus(Long accountId, String goodsId) {
        return jdbcTemplate.query("""
                SELECT sku_key, property_text, price, quantity, sku_id, features
                  FROM xianyu_goods_sku
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? ORDER BY id
                """, (rs, rowNum) -> {
            Map<String, Object> sku = new LinkedHashMap<>();
            sku.put("skuKey", rs.getString("sku_key"));
            sku.put("skuText", rs.getString("property_text"));
            Integer priceInCents = nullableInteger(rs, "price");
            sku.put("price", priceInCents == null ? null : BigDecimal.valueOf(priceInCents, 2));
            sku.put("stock", nullableInteger(rs, "quantity"));
            sku.put("skuId", rs.getString("sku_id"));
            Object features = readJson(rs.getString("features"));
            sku.put("features", features);
            Map<?, ?> featureMap = features instanceof Map<?, ?> map ? map : Map.of();
            sku.put("platformStatus", featureMap.get("platformStatus"));
            sku.put("originalPrice", decimal(featureMap.get("originalPrice")));
            sku.put("image", featureMap.get("image"));
            sku.put("syncDifference", featureMap.containsKey("platformStatus")
                    ? "SKU 规格、价格、库存与平台状态已有同步证据"
                    : "平台 SKU 状态和划线价尚未同步");
            List<Map<String, Object>> fulfillment = jdbcTemplate.queryForList("""
                    SELECT delivery_mode deliveryMode, sku_name skuName, kami_config_ids cardPoolIds,
                           kami_delivery_template deliveryTemplate, update_time updatedTime
                      FROM xianyu_goods_auto_delivery_config
                     WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND sku_key=COALESCE(?, '')
                    """, requireTenant(), accountId, goodsId, rs.getString("sku_id"));
            sku.put("fulfillment", fulfillment.isEmpty() ? null : fulfillment.getFirst());
            return sku;
        }, requireTenant(), accountId, goodsId);
    }

    static Map<String, Object> skuEvidence(Map<String, Object> basic, List<Map<String, Object>> skuRows) {
        Integer declaredValue = integer(basic.get("skuCount"));
        int declared = declaredValue == null ? 0 : Math.max(0, declaredValue);
        int verified = skuRows == null ? 0 : skuRows.size();
        String productCoverage = string(basic.get("coverageStatus"));
        String status;
        String message;
        if (declared == verified && declared > 0) {
            status = "FULL";
            message = "主档声明数量与已同步 SKU 子项一致";
        } else if (declared == 0 && verified == 0 && "FULL".equals(productCoverage)) {
            status = "EMPTY_VERIFIED";
            message = "平台完整快照确认当前商品没有可拆分 SKU";
        } else if (declared > 0 && verified == 0) {
            status = "UNSYNCED";
            message = "主档声明 " + declared + " 个 SKU，但子项尚未同步；不能视为无 SKU";
        } else {
            status = "PARTIAL";
            message = "主档声明 " + declared + " 个 SKU，已验证 " + verified + " 个；请重新同步商品详情";
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("declaredCount", declaredValue);
        evidence.put("verifiedCount", verified);
        evidence.put("coverageStatus", status);
        evidence.put("consistent", declaredValue != null && declared == verified);
        evidence.put("message", message);
        return evidence;
    }

    private Map<String, Object> marketing(Long accountId, String goodsId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT xianyu_auto_delivery_on, xianyu_auto_reply_on, xianyu_auto_rate_on,
                       xianyu_auto_polish_on, human_intervention_on, last_polish_time
                  FROM xianyu_goods_config
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("autoDeliveryEnabled", rs.getInt("xianyu_auto_delivery_on") == 1);
            value.put("autoReplyEnabled", rs.getInt("xianyu_auto_reply_on") == 1);
            value.put("autoRateEnabled", rs.getInt("xianyu_auto_rate_on") == 1);
            value.put("autoPolishEnabled", rs.getInt("xianyu_auto_polish_on") == 1);
            value.put("humanTakeoverEnabled", rs.getInt("human_intervention_on") == 1);
            value.put("lastPolishTime", nullableLong(rs, "last_polish_time"));
            value.put("fanPrice", null);
            value.put("bargain", null);
            value.put("coinDeduction", null);
            value.put("marketingCoverageStatus", "UNSYNCED");
            return value;
        }, requireTenant(), accountId, goodsId);
        if (!rows.isEmpty()) return rows.getFirst();
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("autoDeliveryEnabled", false);
        unknown.put("autoReplyEnabled", false);
        unknown.put("autoRateEnabled", false);
        unknown.put("autoPolishEnabled", false);
        unknown.put("humanTakeoverEnabled", false);
        unknown.put("lastPolishTime", null);
        unknown.put("fanPrice", null);
        unknown.put("bargain", null);
        unknown.put("coinDeduction", null);
        unknown.put("marketingCoverageStatus", "UNSYNCED");
        return unknown;
    }

    private Map<String, Object> metricWindow(Long accountId, String goodsId, int days) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT COUNT(DISTINCT metric_date) sample_days,
                       SUM(exposure_count) exposure_count, SUM(exposure_uv_count) exposure_uv_count,
                       SUM(visitor_count) visitor_count,
                       SUM(click_count) click_count, SUM(favorite_count) favorite_count,
                       SUM(inquiry_count) inquiry_count, SUM(inquiry_buyer_count) inquiry_buyer_count,
                       SUM(paid_order_count) paid_order_count, SUM(paid_buyer_count) paid_buyer_count,
                       SUM(completed_buyer_count) completed_buyer_count,
                       SUM(refund_buyer_count) refund_buyer_count,
                       SUM(refund_order_count) refund_order_count,
                       SUM(paid_amount) paid_amount, SUM(refund_amount) refund_amount,
                       COUNT(DISTINCT IF(exposure_count IS NOT NULL,metric_date,NULL)) exposure_days,
                       COUNT(DISTINCT IF(exposure_uv_count IS NOT NULL,metric_date,NULL)) exposure_uv_days,
                       COUNT(DISTINCT IF(visitor_count IS NOT NULL,metric_date,NULL)) visitor_days,
                       COUNT(DISTINCT IF(click_count IS NOT NULL,metric_date,NULL)) click_days,
                       COUNT(DISTINCT IF(favorite_count IS NOT NULL,metric_date,NULL)) favorite_days,
                       COUNT(DISTINCT IF(inquiry_count IS NOT NULL,metric_date,NULL)) inquiry_days,
                       COUNT(DISTINCT IF(inquiry_buyer_count IS NOT NULL,metric_date,NULL)) inquiry_buyer_days,
                       COUNT(DISTINCT IF(paid_order_count IS NOT NULL,metric_date,NULL)) paid_order_days,
                       COUNT(DISTINCT IF(paid_buyer_count IS NOT NULL,metric_date,NULL)) paid_buyer_days,
                       COUNT(DISTINCT IF(completed_buyer_count IS NOT NULL,metric_date,NULL)) completed_buyer_days,
                       COUNT(DISTINCT IF(refund_buyer_count IS NOT NULL,metric_date,NULL)) refund_buyer_days,
                       COUNT(DISTINCT IF(refund_order_count IS NOT NULL,metric_date,NULL)) refund_order_days,
                       COUNT(DISTINCT IF(paid_amount IS NOT NULL,metric_date,NULL)) paid_amount_days,
                       COUNT(DISTINCT IF(refund_amount IS NOT NULL,metric_date,NULL)) refund_amount_days,
                       COUNT(*)>COUNT(DISTINCT metric_date) same_day_conflict,
                       CASE WHEN COUNT(*)=0 THEN 'UNSYNCED'
                            WHEN COUNT(DISTINCT metric_date)>=? AND COUNT(DISTINCT source)=1
                                 AND SUM(coverage_status='FULL')=COUNT(*)
                            THEN 'FULL' ELSE 'PARTIAL' END coverage_status,
                       MIN(metric_date) data_start_date, MAX(metric_date) data_date, MAX(synced_at) synced_at,
                       COUNT(DISTINCT source) source_count,
                       GROUP_CONCAT(DISTINCT source ORDER BY source SEPARATOR ',') sources
                  FROM xianyu_goods_metric_daily
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                   AND metric_date >= DATE_SUB(CURRENT_DATE(), INTERVAL ? DAY)
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleDays", rs.getInt("sample_days"));
            row.put("exposureCount", nullableLong(rs, "exposure_count"));
            row.put("exposureUvCount", nullableLong(rs, "exposure_uv_count"));
            row.put("visitorCount", nullableLong(rs, "visitor_count"));
            row.put("clickCount", nullableLong(rs, "click_count"));
            row.put("favoriteCount", nullableLong(rs, "favorite_count"));
            row.put("inquiryCount", nullableLong(rs, "inquiry_count"));
            row.put("inquiryBuyerCount", nullableLong(rs, "inquiry_buyer_count"));
            row.put("paidOrderCount", nullableLong(rs, "paid_order_count"));
            row.put("paidBuyerCount", nullableLong(rs, "paid_buyer_count"));
            row.put("completedBuyerCount", nullableLong(rs, "completed_buyer_count"));
            row.put("refundBuyerCount", nullableLong(rs, "refund_buyer_count"));
            row.put("refundOrderCount", nullableLong(rs, "refund_order_count"));
            row.put("paidAmount", rs.getBigDecimal("paid_amount"));
            row.put("refundAmount", rs.getBigDecimal("refund_amount"));
            row.put("exposureDays", rs.getInt("exposure_days"));
            row.put("exposureUvDays", rs.getInt("exposure_uv_days"));
            row.put("visitorDays", rs.getInt("visitor_days"));
            row.put("clickDays", rs.getInt("click_days"));
            row.put("favoriteDays", rs.getInt("favorite_days"));
            row.put("inquiryDays", rs.getInt("inquiry_days"));
            row.put("inquiryBuyerDays", rs.getInt("inquiry_buyer_days"));
            row.put("paidOrderDays", rs.getInt("paid_order_days"));
            row.put("paidBuyerDays", rs.getInt("paid_buyer_days"));
            row.put("completedBuyerDays", rs.getInt("completed_buyer_days"));
            row.put("refundBuyerDays", rs.getInt("refund_buyer_days"));
            row.put("refundOrderDays", rs.getInt("refund_order_days"));
            row.put("paidAmountDays", rs.getInt("paid_amount_days"));
            row.put("refundAmountDays", rs.getInt("refund_amount_days"));
            row.put("sameDayConflict", rs.getBoolean("same_day_conflict"));
            row.put("coverageStatus", rs.getString("coverage_status"));
            row.put("dataStartDate", rs.getDate("data_start_date") == null ? null : rs.getDate("data_start_date").toLocalDate());
            row.put("dataDate", rs.getDate("data_date") == null ? null : rs.getDate("data_date").toLocalDate());
            row.put("syncedAt", instant(rs, "synced_at"));
            row.put("sourceCount", rs.getInt("source_count"));
            row.put("sources", rs.getString("sources"));
            return row;
        }, days, requireTenant(), accountId, goodsId, Math.max(0, days - 1));
        return metricWindowResponse(rows.getFirst(), days, accountId, goodsId, Instant.now());
    }

    private Map<String, Object> eventRow(ResultSet rs) throws SQLException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", rs.getLong("id"));
        event.put("eventType", rs.getString("event_type"));
        event.put("eventOrigin", rs.getString("event_origin"));
        event.put("outcomeState", rs.getString("outcome_state"));
        event.put("dataSource", rs.getString("data_source"));
        event.put("operatorUserId", nullableLong(rs, "operator_user_id"));
        event.put("operatorUsername", rs.getString("operator_username"));
        event.put("requestId", rs.getString("request_id"));
        event.put("idempotencyKey", rs.getString("idempotency_key"));
        event.put("jobId", nullableLong(rs, "batch_job_id"));
        event.put("taskItemId", nullableLong(rs, "batch_item_id"));
        event.put("platformRequestId", rs.getString("platform_request_id"));
        event.put("before", readJson(rs.getString("before_json")));
        event.put("after", readJson(rs.getString("after_json")));
        event.put("fieldDiff", readJson(rs.getString("field_diff_json")));
        event.put("rawSnapshotHash", rs.getString("raw_snapshot_hash"));
        event.put("platformResponseCode", rs.getString("platform_response_code"));
        event.put("errorMessage", rs.getString("error_message"));
        event.put("createdTime", instant(rs, "created_time"));
        event.put("presentation", eventPresentation(
                rs.getString("event_type"), rs.getString("outcome_state"), rs.getString("data_source"),
                rs.getString("event_origin"), rs.getString("error_message")));
        return event;
    }

    static Map<String, Object> eventPresentation(String eventType, String outcomeState, String dataSource,
                                                 String eventOrigin, String errorMessage) {
        String title = PRODUCT_EVENT_LABELS.getOrDefault(eventType, "其他商品事件");
        String outcomeLabel = PRODUCT_OUTCOME_LABELS.getOrDefault(outcomeState, "状态待核对");
        String sourceLabel = PRODUCT_SOURCE_LABELS.getOrDefault(dataSource, "其他来源");
        String originLabel = PRODUCT_ORIGIN_LABELS.getOrDefault(eventOrigin, "来源待核对");
        String nextAction;
        if ("UNKNOWN".equals(outcomeState)) {
            nextAction = "先到平台核对实际结果；确认前不要自动重试。";
        } else if ("FAILED".equals(outcomeState)) {
            nextAction = "查看失败原因，修复后使用新的请求重新执行。";
        } else if ("PLATFORM_REQUESTED".equals(outcomeState) || "RUNNING".equals(outcomeState)) {
            nextAction = "等待平台回执；超时后进入结果未知并人工核对。";
        } else if ("CANCEL_REQUESTED".equals(outcomeState)) {
            nextAction = "等待未开始项停止；已请求平台的项目仍需核对结果。";
        } else {
            nextAction = "当前无需额外处理。";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("outcomeLabel", outcomeLabel);
        result.put("sourceLabel", sourceLabel);
        result.put("originLabel", originLabel);
        result.put("summary", title + "，结果：" + outcomeLabel + (errorMessage == null || errorMessage.isBlank() ? "" : "；原因：" + errorMessage));
        result.put("nextAction", nextAction);
        return result;
    }

    static Map<String, Object> timelineState(Long accountId, String goodsId,
                                             List<Map<String, Object>> timeline, Instant checkedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", Map.of("accountId", accountId, "goodsId", goodsId,
                "label", "当前店铺 · 当前商品"));
        result.put("eventCount", timeline.size());
        result.put("lastCheckedAt", checkedAt);
        result.put("latestEventAt", timeline.isEmpty() ? null : timeline.getFirst().get("createdTime"));
        result.put("source", "LOCAL_DATABASE");
        if (timeline.isEmpty()) {
            result.put("emptyState", Map.of(
                    "title", "当前范围尚无商品事件",
                    "reason", "已检查当前店铺和商品的本地事件记录，没有找到可验证事件。",
                    "nextAction", "完成商品同步、本地编辑、自动化保存或批量任务后，可在这里核对事件证据。"));
        } else {
            result.put("emptyState", null);
        }
        return result;
    }

    static Map<String, Object> metricWindowResponse(Map<String, Object> row, int days, Long accountId,
                                                    String goodsId, Instant checkedAt) {
        int samples = integer(row.get("sampleDays")) == null ? 0 : integer(row.get("sampleDays"));
        String sourceCsv = string(row.get("sources"));
        List<String> sources = sourceCsv == null || sourceCsv.isBlank() ? List.of() : List.of(sourceCsv.split(","));
        String source = sources.isEmpty() ? "UNSYNCED" : sources.size() == 1 ? sources.getFirst() : "MULTIPLE";
        String coverageStatus = samples == 0 ? "UNSYNCED" : string(row.get("coverageStatus"));
        Object syncedAt = samples == 0 ? null : row.get("syncedAt");
        boolean sameDayConflict = Boolean.TRUE.equals(row.get("sameDayConflict"));
        boolean valuesAvailable = samples > 0 && !sameDayConflict;
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("windowDays", days);
        metric.put("sampleDays", samples == 0 ? null : samples);
        metric.put("exposureCount", valuesAvailable ? row.get("exposureCount") : null);
        metric.put("exposureUvCount", valuesAvailable ? row.get("exposureUvCount") : null);
        metric.put("visitorCount", valuesAvailable ? row.get("visitorCount") : null);
        metric.put("clickCount", valuesAvailable ? row.get("clickCount") : null);
        metric.put("favoriteCount", valuesAvailable ? row.get("favoriteCount") : null);
        metric.put("inquiryCount", valuesAvailable ? row.get("inquiryCount") : null);
        metric.put("inquiryBuyerCount", valuesAvailable ? row.get("inquiryBuyerCount") : null);
        metric.put("paidOrderCount", valuesAvailable ? row.get("paidOrderCount") : null);
        metric.put("paidBuyerCount", valuesAvailable ? row.get("paidBuyerCount") : null);
        metric.put("completedBuyerCount", valuesAvailable ? row.get("completedBuyerCount") : null);
        metric.put("refundBuyerCount", valuesAvailable ? row.get("refundBuyerCount") : null);
        metric.put("refundOrderCount", valuesAvailable ? row.get("refundOrderCount") : null);
        metric.put("paidAmount", valuesAvailable ? row.get("paidAmount") : null);
        metric.put("refundAmount", valuesAvailable ? row.get("refundAmount") : null);
        metric.put("valueAvailability", samples == 0 ? "UNSYNCED" : sameDayConflict ? "SOURCE_CONFLICT" : "AVAILABLE");
        metric.put("valueMessage", sameDayConflict
                ? "同一自然日存在多个来源，汇总值已隐藏；请先确认权威来源后再分析。" : null);
        metric.put("coverageStatus", coverageStatus);
        metric.put("coverage", coverage(coverageStatus, samples, days));
        metric.put("source", source);
        metric.put("sources", sources);
        metric.put("dataStartDate", samples == 0 ? null : row.get("dataStartDate"));
        metric.put("dataDate", samples == 0 ? null : row.get("dataDate"));
        metric.put("syncedAt", syncedAt);
        metric.put("lastCheckedAt", checkedAt);
        metric.put("scope", Map.of("accountId", accountId, "goodsId", goodsId, "windowDays", days,
                "label", "当前商品 · 最近 " + days + " 天"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("exposureCount", metricField(metric.get("exposureCount"), source, coverageStatus,
                integer(row.get("exposureDays")), days, syncedAt, "商品在所选时间范围内获得展示的累计次数"));
        fields.put("exposureUvCount", metricField(metric.get("exposureUvCount"), source, coverageStatus,
                integer(row.get("exposureUvDays")), days, syncedAt, "数据源按日去重后提供的曝光人数；跨日相加不等于跨周期绝对去重人数"));
        fields.put("visitorCount", metricField(metric.get("visitorCount"), source, coverageStatus,
                integer(row.get("visitorDays")), days, syncedAt, "进入商品详情的去重访客累计数"));
        fields.put("clickCount", metricField(metric.get("clickCount"), source, coverageStatus,
                integer(row.get("clickDays")), days, syncedAt, "可验证数据源记录的商品点击累计数"));
        fields.put("favoriteCount", metricField(metric.get("favoriteCount"), source, coverageStatus,
                integer(row.get("favoriteDays")), days, syncedAt, "商品新增收藏的累计次数"));
        fields.put("inquiryCount", metricField(metric.get("inquiryCount"), source, coverageStatus,
                integer(row.get("inquiryDays")), days, syncedAt, "与该商品关联的新咨询累计数"));
        fields.put("inquiryBuyerCount", metricField(metric.get("inquiryBuyerCount"), source, coverageStatus,
                integer(row.get("inquiryBuyerDays")), days, syncedAt, "发起商品咨询的按日去重买家人数"));
        fields.put("paidOrderCount", metricField(metric.get("paidOrderCount"), source, coverageStatus,
                integer(row.get("paidOrderDays")), days, syncedAt, "与该商品关联且已支付的订单累计数"));
        fields.put("paidBuyerCount", metricField(metric.get("paidBuyerCount"), source, coverageStatus,
                integer(row.get("paidBuyerDays")), days, syncedAt, "支付该商品订单的按日去重买家人数"));
        fields.put("completedBuyerCount", metricField(metric.get("completedBuyerCount"), source, coverageStatus,
                integer(row.get("completedBuyerDays")), days, syncedAt, "该商品交易已完成的按日去重买家人数"));
        fields.put("refundBuyerCount", metricField(metric.get("refundBuyerCount"), source, coverageStatus,
                integer(row.get("refundBuyerDays")), days, syncedAt, "该商品发生退款的按日去重买家人数"));
        fields.put("refundOrderCount", metricField(metric.get("refundOrderCount"), source, coverageStatus,
                integer(row.get("refundOrderDays")), days, syncedAt, "与该商品关联且发生退款的订单累计数"));
        fields.put("paidAmount", metricField(metric.get("paidAmount"), source, coverageStatus,
                integer(row.get("paidAmountDays")), days, syncedAt, "与该商品关联且金额已同步的支付金额合计"));
        fields.put("refundAmount", metricField(metric.get("refundAmount"), source, coverageStatus,
                integer(row.get("refundAmountDays")), days, syncedAt, "与该商品关联且金额已同步的退款金额合计"));
        metric.put("fields", fields);
        metric.put("funnel", buyerFunnel(fields, source, days));
        if (samples == 0) {
            metric.put("emptyState", Map.of(
                    "title", "最近 " + days + " 天指标尚未同步",
                    "reason", "已检查本地指标缓存，没有找到可验证样本；这不代表曝光、访客或成交为 0。",
                    "nextAction", "平台指标适配器接入前请到闲鱼端核对，当前系统不会据此生成经营结论。"));
        } else {
            metric.put("emptyState", null);
        }
        return metric;
    }

    static Map<String, Object> buyerFunnel(Map<String, Object> fields, String source, int windowDays) {
        List<Map<String, Object>> stages = List.of(
                funnelStage("EXPOSURE", "曝光人数", "exposureUvCount", fields),
                funnelStage("DETAIL", "详情访客", "visitorCount", fields),
                funnelStage("INQUIRY", "咨询买家", "inquiryBuyerCount", fields),
                funnelStage("PAID", "支付买家", "paidBuyerCount", fields),
                funnelStage("COMPLETED", "完成买家", "completedBuyerCount", fields),
                funnelStage("REFUND", "退款买家", "refundBuyerCount", fields));
        List<Map<String, Object>> transitions = List.of(
                funnelTransition(stages.get(0), stages.get(1), source, windowDays),
                funnelTransition(stages.get(1), stages.get(2), source, windowDays),
                funnelTransition(stages.get(2), stages.get(3), source, windowDays),
                funnelTransition(stages.get(3), stages.get(4), source, windowDays),
                funnelTransition(stages.get(3), stages.get(5), source, windowDays));
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> transition : transitions) {
            if (Boolean.TRUE.equals(transition.get("monotonicityWarning"))) {
                warnings.add(string(transition.get("fromLabel")) + "少于" + string(transition.get("toLabel"))
                        + "，请检查数据源口径或跨日去重方式");
            }
        }
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("definition", "按数据源日去重人数累计构建；完成和退款均从支付买家分支计算");
        funnel.put("source", source);
        funnel.put("windowDays", windowDays);
        funnel.put("stages", stages);
        funnel.put("transitions", transitions);
        funnel.put("warnings", warnings);
        funnel.put("hasAnyData", stages.stream().anyMatch(stage -> stage.get("value") != null));
        return funnel;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> funnelStage(String key, String label, String fieldKey,
                                                    Map<String, Object> fields) {
        Map<String, Object> field = (Map<String, Object>) fields.get(fieldKey);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("key", key);
        stage.put("label", label);
        stage.put("fieldKey", fieldKey);
        stage.put("value", field == null ? null : field.get("value"));
        stage.put("coverage", field == null ? coverage("UNSYNCED", 0, 1) : field.get("coverage"));
        stage.put("definition", field == null ? null : field.get("definition"));
        return stage;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> funnelTransition(Map<String, Object> from, Map<String, Object> to,
                                                         String source, int windowDays) {
        Number fromValue = from.get("value") instanceof Number number ? number : null;
        Number toValue = to.get("value") instanceof Number number ? number : null;
        Map<String, Object> fromCoverage = from.get("coverage") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> toCoverage = to.get("coverage") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Integer fromDays = integer(fromCoverage.get("numerator"));
        Integer toDays = integer(toCoverage.get("numerator"));
        boolean compatible = fromValue != null && toValue != null && fromDays != null && fromDays > 0
                && Objects.equals(fromDays, toDays) && !"UNSYNCED".equals(source) && !"MULTIPLE".equals(source);
        BigDecimal rate = null;
        String status;
        String reason = null;
        if (!compatible) {
            status = "UNAVAILABLE";
            reason = fromValue == null || toValue == null ? "上下游人数尚未完整同步"
                    : "上下游覆盖天数或来源不兼容，不能计算";
        } else if (fromValue.longValue() == 0L) {
            status = "NOT_COMPUTABLE";
            reason = "上游人数真实为 0，转化率不可计算";
        } else {
            rate = BigDecimal.valueOf(toValue.longValue())
                    .divide(BigDecimal.valueOf(fromValue.longValue()), 6, RoundingMode.HALF_UP);
            status = fromDays >= windowDays ? "FULL" : "PARTIAL";
            if ("PARTIAL".equals(status)) reason = "仅基于 " + fromDays + "/" + windowDays + " 天兼容样本";
        }
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("from", from.get("key"));
        transition.put("fromLabel", from.get("label"));
        transition.put("to", to.get("key"));
        transition.put("toLabel", to.get("label"));
        transition.put("rate", rate);
        transition.put("ratePercent", rate == null ? null : rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
        transition.put("status", status);
        transition.put("formula", string(to.get("label")) + " ÷ " + string(from.get("label")));
        transition.put("source", source);
        transition.put("coverage", coverage(status, fromDays == null ? 0 : fromDays, windowDays));
        transition.put("reason", reason);
        transition.put("monotonicityWarning", fromValue != null && toValue != null
                && toValue.longValue() > fromValue.longValue());
        return transition;
    }

    private static Map<String, Object> metricField(Object value, String source, String coverageStatus,
                                                   Integer knownDays, int windowDays, Object syncedAt,
                                                   String definition) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("value", value);
        field.put("source", source);
        field.put("coverage", coverage(value == null ? "UNSYNCED" : coverageStatus,
                knownDays == null ? 0 : knownDays, windowDays));
        field.put("syncedAt", syncedAt);
        field.put("definition", definition);
        return field;
    }

    private static Map<String, Object> coverage(String status, int numerator, int denominator) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("status", status == null ? "UNSYNCED" : status);
        coverage.put("numerator", Math.max(0, Math.min(numerator, denominator)));
        coverage.put("denominator", denominator);
        return coverage;
    }

    private Map<String, Object> batchRow(ResultSet rs) throws SQLException {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", rs.getLong("id"));
        job.put("batchId", rs.getString("batch_id"));
        job.put("requestId", rs.getString("request_id"));
        job.put("idempotencyKey", rs.getString("idempotency_key"));
        job.put("operationType", rs.getString("operation_type"));
        job.put("selectionMode", rs.getString("selection_mode"));
        job.put("operationParams", readJson(rs.getString("operation_params_json")));
        job.put("executionChannel", rs.getString("execution_channel"));
        job.put("notificationEvidence", readJson(rs.getString("notification_evidence_json")));
        job.put("status", rs.getString("status"));
        job.put("selectedCount", rs.getInt("selected_count"));
        job.put("conflictCount", rs.getInt("conflict_count"));
        job.put("executableCount", rs.getInt("executable_count"));
        job.put("successCount", rs.getInt("success_count"));
        job.put("failedCount", rs.getInt("failed_count"));
        job.put("skippedCount", rs.getInt("skipped_count"));
        job.put("unknownCount", rs.getInt("unknown_count"));
        job.put("cancelledCount", rs.getInt("cancelled_count"));
        job.put("progressPercent", rs.getBigDecimal("progress_percent"));
        job.put("maxOperationsPerMinute", rs.getInt("max_operations_per_minute"));
        int remaining = Math.max(0, rs.getInt("executable_count") - rs.getInt("success_count")
                - rs.getInt("failed_count") - rs.getInt("skipped_count")
                - rs.getInt("unknown_count") - rs.getInt("cancelled_count"));
        job.put("estimatedWaitSecondsUpperBound", remaining == 0 ? 0
                : (int) Math.ceil(remaining * 60d / Math.max(1, rs.getInt("max_operations_per_minute"))));
        job.put("confirmationSummary", rs.getString("confirmation_summary"));
        job.put("operatorUserId", nullableLong(rs, "operator_user_id"));
        job.put("operatorUsername", rs.getString("operator_username"));
        job.put("startedTime", instant(rs, "started_time"));
        job.put("completedTime", instant(rs, "completed_time"));
        job.put("cancelRequestedTime", instant(rs, "cancel_requested_time"));
        job.put("cancellationReason", rs.getString("cancellation_reason"));
        job.put("recoveryCount", rs.getInt("recovery_count"));
        job.put("lastDispatchTime", instant(rs, "last_dispatch_time"));
        job.put("createdTime", instant(rs, "created_time"));
        return job;
    }

    private Map<String, Object> batchItemRow(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", rs.getLong("id"));
        item.put("accountId", rs.getLong("xianyu_account_id"));
        item.put("accountNote", rs.getString("account_note"));
        item.put("goodsId", rs.getString("xy_goods_id"));
        item.put("title", rs.getString("title"));
        item.put("operationType", rs.getString("operation_type"));
        item.put("expectedGoodsVersion", nullableLong(rs, "expected_goods_version"));
        item.put("oldValue", readJson(rs.getString("old_value_json")));
        item.put("newValue", readJson(rs.getString("new_value_json")));
        item.put("status", rs.getString("status"));
        item.put("conflictCode", rs.getString("conflict_code"));
        item.put("conflictMessage", rs.getString("conflict_message"));
        item.put("attemptCount", rs.getInt("attempt_count"));
        item.put("maxAttempts", rs.getInt("max_attempts"));
        item.put("nextRetryTime", instant(rs, "next_retry_time"));
        item.put("outcomeState", rs.getString("outcome_state"));
        item.put("platformResponseCode", rs.getString("platform_response_code"));
        item.put("platformRequestId", rs.getString("platform_request_id"));
        item.put("errorCode", rs.getString("error_code"));
        item.put("result", readJson(rs.getString("result_json")));
        item.put("errorMessage", rs.getString("error_message"));
        item.put("startedTime", instant(rs, "started_time"));
        item.put("completedTime", instant(rs, "completed_time"));
        return item;
    }

    private List<ProductRef> resolveSelection(BatchRequest request) {
        LinkedHashSet<ProductRef> refs = new LinkedHashSet<>();
        if ("FILTER_SNAPSHOT".equals(request.selectionMode())) {
            ProductFilter filter = normalizeFilter(request.filter());
            QueryParts query=queryParts(filter,true);
            List<Map<String,Object>> products=namedJdbc.query("SELECT goods.*,account.account_note,account.unb "+query.fromWhere()
                    +" ORDER BY goods.updated_time DESC,goods.id DESC LIMIT 1001",query.params(),(rs,row)->productRow(rs));
            products.forEach(product -> refs.add(new ProductRef(
                    ((Number) product.get("accountId")).longValue(), string(product.get("goodsId")))));
            if (products.size() > MAX_BATCH_ITEMS) throw new BusinessException(400, "跨页批量单次最多1000个商品，请缩小筛选范围");
        } else {
            if (request.items() != null) refs.addAll(request.items());
        }
        if (request.excludedItems() != null) refs.removeAll(request.excludedItems());
        if (refs.isEmpty()) throw new BusinessException(400, "请选择至少一个商品");
        if (refs.size() > MAX_BATCH_ITEMS) throw new BusinessException(400, "批量任务单次最多1000个商品");
        refs.forEach(ref -> {
            if (ref == null || ref.accountId() == null || ref.accountId() <= 0 || trim(ref.goodsId()) == null) {
                throw new BusinessException(400, "商品选择范围包含无效账号或商品ID");
            }
            accountAccessService.requireAccess(ref.accountId());
        });
        return List.copyOf(refs);
    }

    private BatchRequest normalizeBatchRequest(BatchRequest request, boolean forCreate) {
        if (request == null) throw new BusinessException(400, "批量任务参数不能为空");
        String operation = requireEnum(request.operationType(), BATCH_OPERATIONS, "批量操作");
        String selectionMode = request.selectionMode() == null ? "EXPLICIT_IDS"
                : requireEnum(request.selectionMode(), Set.of("EXPLICIT_IDS", "FILTER_SNAPSHOT"), "选择范围");
        String requestId = forCreate ? requireText(request.requestId(), "requestId", 80) : trim(request.requestId());
        String idempotencyKey = forCreate
                ? requireText(request.idempotencyKey() == null ? requestId : request.idempotencyKey(), "idempotencyKey", 100)
                : trim(request.idempotencyKey());
        int rate = request.maxOperationsPerMinute() == null ? 10
                : Math.max(1, Math.min(request.maxOperationsPerMinute(), 30));
        Map<String, Object> params = request.operationParams() == null ? Map.of() : request.operationParams();
        if ("CHANGE_PRICE".equals(operation)) {
            BigDecimal price = decimal(params.get("price"));
            if (price == null || price.signum() <= 0) throw new BusinessException(400, "批量改价需要大于0的price");
            if (Math.max(price.stripTrailingZeros().scale(), 0) > 2) {
                throw new BusinessException(400, "批量改价的price最多保留两位小数");
            }
            if (price.compareTo(MAX_PRODUCT_PRICE) > 0) {
                throw new BusinessException(400, "批量改价的price不能超过" + MAX_PRODUCT_PRICE.toPlainString());
            }
        }
        if ("CHANGE_STOCK".equals(operation)) {
            Integer stock = integer(params.get("stock"));
            if (stock == null || stock < 0 || stock > 9999) {
                throw new BusinessException(400, "批量改库存需要0至9999的整数stock");
            }
        }
        return new BatchRequest(requestId, idempotencyKey, operation, selectionMode, request.items(), request.excludedItems(),
                request.filter(), params, rate, request.confirmationText(), trim(request.previewToken()));
    }

    private Map<String, Object> findProduct(Long accountId, String goodsId) {
        requireProductAccess(accountId, goodsId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT goods.title, goods.support_policy, goods.location_text, goods.status,
                       goods.product_source, goods.publish_channel, goods.sku_count,
                       goods.sync_status, goods.coverage_status, goods.row_version, goods.sold_price, goods.stock,
                       account.status account_status,
                       CASE WHEN cookie.cookie_status=1 AND cookie.cookie_text IS NOT NULL AND cookie.cookie_text<>'' THEN 1 ELSE 0 END credential_ready
                  FROM xianyu_goods goods JOIN xianyu_account account
                    ON account.id=goods.xianyu_account_id AND account.tenant_id=goods.tenant_id
                  LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id=account.id AND cookie.tenant_id=goods.tenant_id
                 WHERE goods.tenant_id=? AND goods.xianyu_account_id=? AND goods.xy_good_id=?
                """, requireTenant(), accountId, goodsId);
        if (rows.isEmpty()) throw new BusinessException(404, "商品不存在：" + goodsId);
        return rows.getFirst();
    }

    private String conflict(String operation, Map<String, Object> params, Map<String, Object> product) {
        Integer status = integer(product.get("status"));
        String source = string(product.get("product_source"));
        if (!Integer.valueOf(1).equals(integer(product.get("account_status")))) return "店铺当前未启用";
        if (!Integer.valueOf(1).equals(integer(product.get("credential_ready")))) return "店铺授权凭据不可用";
        String syncStatus = string(product.get("sync_status"));
        if (!"SYNC".equals(operation) && !"SUCCEEDED".equals(syncStatus)) return "商品数据不是最新平台真值，请先同步";
        if ("LOCAL_DRAFT".equals(source) && !"SYNC".equals(operation)) return "本地草稿尚无平台商品，不能执行平台操作";
        if ("ON_SALE".equals(operation) && Integer.valueOf(0).equals(status)) return "商品已经在售";
        if ("OFF_SHELF".equals(operation) && status != null && Set.of(1, -1, -98).contains(status)) return "商品已经下架或删除";
        if ("DELETE".equals(operation) && Integer.valueOf(-1).equals(status)) return "商品已经删除";
        if ("CHANGE_PRICE".equals(operation)) {
            if (decimal(params.get("price")) == null) return "缺少目标价格";
            if (!platformWritePolicy.enabled()) return "当前环境关闭真实平台写入，改价仅允许隔离 QA Mock";
            Integer skuCount = integer(product.get("sku_count"));
            if (skuCount == null) return "规格数量尚未同步，请先同步商品详情";
            if (skuCount > 1) return "多规格商品需要逐 SKU 改价，当前统一改价会破坏规格差异";
        }
        if ("CHANGE_STOCK".equals(operation)) {
            Integer stock = integer(params.get("stock"));
            if (stock == null) return "缺少目标库存";
            if (stock == 0) return "平台零库存不作为编辑值提交，请使用下架操作";
            if (stock > 9999) return "平台库存不能超过9999";
            if (!platformWritePolicy.enabled()) return "当前环境关闭真实平台写入，库存修改仅允许隔离 QA Mock";
            Integer skuCount = integer(product.get("sku_count"));
            if (skuCount == null) return "规格数量尚未同步，请先同步商品详情";
            if (skuCount > 1) return "多规格商品需要逐 SKU 改库存，当前统一库存会破坏规格差异";
        }
        return null;
    }

    private Map<String, Object> oldValue(Map<String, Object> product) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", product.get("status"));
        value.put("price", product.get("sold_price"));
        value.put("stock", product.get("stock"));
        return value;
    }

    private Map<String, Object> selectionSnapshot(BatchRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("filter", request.filter());
        snapshot.put("excludedItems", request.excludedItems() == null ? List.of() : request.excludedItems());
        return snapshot;
    }

    private Map<String, Object> filteredSummary(QueryParts query, int metricWindowDays) {
        Map<String, Object> aggregate = namedJdbc.queryForMap("""
                SELECT COUNT(*) productCount,
                       SUM(goods.sold_price IS NOT NULL AND goods.sold_price<>'') knownPriceCount,
                       AVG(CASE WHEN goods.sold_price REGEXP '^[0-9]+(\\.[0-9]+)?$' THEN CAST(goods.sold_price AS DECIMAL(12,2)) END) averagePrice,
                       SUM(goods.stock IS NOT NULL) knownStockCount,
                       SUM(goods.stock) knownStockTotal,
                       SUM(goods.coverage_status='UNSYNCED') unsyncedCount,
                       MAX(goods.last_synced_time) lastSyncedTime
                """ + query.fromWhere(), query.params());
        MapSqlParameterSource metricParams = copy(query.params())
                .addValue("metricDays", Math.max(0, metricWindowDays - 1))
                .addValue("metricWindowDays", metricWindowDays);
        Map<String, Object> metrics = namedJdbc.queryForMap("""
                SELECT COUNT(metric.id) sampleRows, COUNT(DISTINCT metric.metric_date) sampleDays,
                       MAX(metric.metric_date) dataDate, MAX(metric.synced_at) metricSyncedAt,
                       CASE WHEN COUNT(metric.id)=0 THEN 'UNSYNCED'
                            WHEN COUNT(DISTINCT metric.metric_date)>=:metricWindowDays
                                 AND COUNT(DISTINCT metric.source)=1
                                 AND SUM(metric.coverage_status='FULL')=COUNT(metric.id)
                            THEN 'FULL' ELSE 'PARTIAL' END metricCoverageStatus,
                       SUM(metric.exposure_count) exposureCount, SUM(metric.visitor_count) visitorCount,
                       SUM(metric.inquiry_count) inquiryCount, SUM(metric.paid_order_count) paidOrderCount
                FROM (SELECT goods.tenant_id, goods.xianyu_account_id, goods.xy_good_id
                """ + query.fromWhere() + ") filtered LEFT JOIN xianyu_goods_metric_daily metric"
                + " ON metric.tenant_id=filtered.tenant_id AND metric.xianyu_account_id=filtered.xianyu_account_id"
                + " AND metric.xy_goods_id=filtered.xy_good_id AND metric.metric_date>=DATE_SUB(CURRENT_DATE(), INTERVAL :metricDays DAY)", metricParams);
        Map<String, Object> summary = new LinkedHashMap<>(aggregate);
        long samples = metrics.get("sampleRows") instanceof Number n ? n.longValue() : 0;
        int sampleDays = samples == 0 || !(metrics.get("sampleDays") instanceof Number n) ? 0 : n.intValue();
        String metricCoverageStatus = samples == 0 ? "UNSYNCED" : string(metrics.get("metricCoverageStatus"));
        summary.put("metricWindowDays", metricWindowDays);
        summary.put("metricCoverageStatus", metricCoverageStatus);
        summary.put("metricCoverage", coverage(metricCoverageStatus, sampleDays, metricWindowDays));
        summary.put("metricSampleDays", samples == 0 ? null : sampleDays);
        summary.put("metricDataDate", samples == 0 ? null : metrics.get("dataDate"));
        summary.put("metricSyncedAt", samples == 0 ? null : metrics.get("metricSyncedAt"));
        summary.put("exposureCount", samples == 0 ? null : metrics.get("exposureCount"));
        summary.put("visitorCount", samples == 0 ? null : metrics.get("visitorCount"));
        summary.put("inquiryCount", samples == 0 ? null : metrics.get("inquiryCount"));
        summary.put("paidOrderCount", samples == 0 ? null : metrics.get("paidOrderCount"));
        return summary;
    }

    private void requireProductAccess(Long accountId, String goodsId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        requireText(goodsId, "商品ID", 100);
        accountAccessService.requireAccess(accountId);
    }

    private String itemScopeCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1=0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private void audit(Long accountId, String type, String description, String requestId,
                       String outcome, Object request, Object result) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("商品管理");
        log.setOperationDesc(description);
        log.setOperationStatus(1);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("LOCAL");
        log.setRequestParams(json(request));
        log.setResponseResult(json(result));
        operationLogService.log(log);
    }

    private void auditChange(Long accountId, String goodsId, String type, String description, String requestId,
                             String outcome, Object command, Map<String, Object> before,
                             Map<String, Object> after, Map<String, Object> fieldDiff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("商品管理");
        log.setOperationDesc(description);
        log.setOperationStatus(1);
        log.setTargetType("PRODUCT");
        log.setTargetId(goodsId);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("LOCAL");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("goodsId", goodsId);
        request.put("requestedChanges", command);
        request.put("before", before);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("after", after);
        result.put("fieldDiff", fieldDiff);
        log.setRequestParams(json(request));
        log.setResponseResult(json(result));
        log.setFieldDiffJson(json(fieldDiff));
        operationLogService.log(log);
    }

    private static Map<String, Object> localDetailsSnapshot(Map<String, Object> product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", product.get("title"));
        snapshot.put("supportPolicy", product.get("support_policy"));
        snapshot.put("location", product.get("location_text"));
        return snapshot;
    }

    private static Map<String, Object> automationSnapshot(Map<String, Object> marketing) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String field : List.of("autoDeliveryEnabled", "autoReplyEnabled", "autoRateEnabled",
                "autoPolishEnabled", "humanTakeoverEnabled")) snapshot.put(field, marketing.get(field));
        return snapshot;
    }

    private static Map<String, Object> automationCommandSnapshot(AutomationUpdate command) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("autoDeliveryEnabled", Boolean.TRUE.equals(command.autoDelivery()));
        snapshot.put("autoReplyEnabled", Boolean.TRUE.equals(command.autoReply()));
        snapshot.put("autoRateEnabled", Boolean.TRUE.equals(command.autoRate()));
        snapshot.put("autoPolishEnabled", Boolean.TRUE.equals(command.autoPolish()));
        snapshot.put("humanTakeoverEnabled", Boolean.TRUE.equals(command.humanTakeover()));
        return snapshot;
    }

    static Map<String, Object> fieldDiff(String mode, Map<String, Object> before, Map<String, Object> after,
                                         Map<String, String> labels) {
        Map<String, Object> fields = new LinkedHashMap<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            Object oldValue = before.get(name);
            Object newValue = after.get(name);
            if (Objects.equals(oldValue, newValue)) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("label", labels.getOrDefault(name, name));
            change.put("before", oldValue);
            change.put("after", newValue);
            fields.put(name, change);
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("mode", mode);
        diff.put("changedFieldCount", fields.size());
        diff.put("changedFields", new ArrayList<>(fields.keySet()));
        diff.put("fields", fields);
        return diff;
    }

    private String operationLabel(String operation) {
        return switch (operation) {
            case "ON_SALE" -> "上架";
            case "OFF_SHELF" -> "下架";
            case "CHANGE_PRICE" -> "改价";
            case "CHANGE_STOCK" -> "改库存";
            case "POLISH" -> "擦亮";
            case "DELETE" -> "删除";
            default -> "同步";
        };
    }

    private String statusBucket(int status, String source) {
        if ("LOCAL_DRAFT".equals(source)) return "DRAFT";
        if (status == 0) return "ON_SALE";
        if (status == 2) return "SOLD";
        if (Set.of(1, -1, -98).contains(status)) return "OFF_SHELF";
        return "OTHER";
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String requireEnum(String value, Set<String> allowed, String label) {
        String normalized = upper(value);
        if (normalized == null || !allowed.contains(normalized))
            throw new BusinessException(400, label + "无效，可选值：" + String.join(",", allowed));
        return normalized;
    }

    private String normalizeOptional(String value, Set<String> allowed, String label) {
        return trim(value) == null ? null : requireEnum(value, allowed, label);
    }

    private String requireText(String value, String label, int maxLength) {
        String normalized = trim(value);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (normalized.length() > maxLength) throw new BusinessException(400, label + "不能超过" + maxLength + "个字符");
        return normalized;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "JSON序列化失败", e);
        }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : source.getValues().keySet()) copy.addValue(name, source.getValue(name));
        return copy;
    }

    private static String upper(String value) {
        String text = trim(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private static int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long longObject(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成商品范围摘要", e);
        }
    }

    private static BigDecimal decimal(Object value) {
        try { return value == null || String.valueOf(value).isBlank() ? null : new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String camel(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean upperNext = false;
        for (char current : value.toCharArray()) {
            if (current == '_') {
                upperNext = true;
            } else {
                result.append(upperNext ? Character.toUpperCase(current) : current);
                upperNext = false;
            }
        }
        return result.toString();
    }

    private record QueryParts(String fromWhere, MapSqlParameterSource params) {}

    public record ProductFilter(String search, List<Long> accountIds, Long groupId, String statusBucket,
                                String source, String publishChannel, Integer metricWindowDays,
                                Integer page, Integer pageSize) {}

    public record SavedFilterCommand(String name,ProductFilter filter,String requestId) {}

    public record LocalProductUpdate(String title, String supportPolicy, String location,
                                     Long expectedVersion, String requestId) {}

    public record AutomationUpdate(Boolean autoDelivery, Boolean autoReply, Boolean autoRate,
                                   Boolean autoPolish, Boolean humanTakeover, String requestId) {}

    public record ProductRef(Long accountId, String goodsId) {}

    public record BatchQuery(String status, String operationType, Long accountId, Long operatorUserId,
                             String search, String createdFrom, String createdTo, Integer limit) {}

    public record BatchRequest(String requestId, String idempotencyKey, String operationType, String selectionMode,
                               List<ProductRef> items, List<ProductRef> excludedItems, ProductFilter filter,
                               Map<String, Object> operationParams, Integer maxOperationsPerMinute,
                               String confirmationText, String previewToken) {}

    public record BatchCandidate(Long accountId, String goodsId, String title, Integer status, long rowVersion,
                                 Map<String, Object> oldValue, boolean executable, String conflictMessage) {}

    public record BatchPreview(String operationType, String selectionMode, int selectedCount,
                               int accountCount, int conflictCount, int executableCount,
                               String confirmationSummary, String previewToken, List<BatchCandidate> items,
                               String executionChannel, String executionNotice) {}
}
