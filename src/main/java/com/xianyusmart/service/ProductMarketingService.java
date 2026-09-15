package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** PRD-07 product marketing intent, platform evidence and safe preflight. */
@Service
public class ProductMarketingService {
    private static final Set<String> MARKETING_CAPABILITIES = Set.of(
            "FAN_PRICE", "BARGAIN_ACTIVITY", "COIN_DEDUCTION", "PAID_PROMOTION");

    private final JdbcTemplate jdbc;
    private final AccountAccessService accountAccess;
    private final OperationLogService operationLogs;
    private final ObjectMapper json;
    private final boolean qaMockEnabled;
    private final long qaTenantId;
    private final Set<Long> qaAccountIds;

    public ProductMarketingService(JdbcTemplate jdbc, AccountAccessService accountAccess,
                                   OperationLogService operationLogs, ObjectMapper json,
                                   @Value("${app.product-marketing.qa-mock.enabled:false}") boolean qaMockEnabled,
                                   @Value("${app.product-marketing.qa-mock.tenant-id:-1}") long qaTenantId,
                                   @Value("${app.product-marketing.qa-mock.account-ids:}") String qaAccountIds) {
        this.jdbc = jdbc;
        this.accountAccess = accountAccess;
        this.operationLogs = operationLogs;
        this.json = json;
        this.qaMockEnabled = qaMockEnabled;
        this.qaTenantId = qaTenantId;
        this.qaAccountIds = parseIds(qaAccountIds);
    }

    public Map<String, Object> state(Long accountId, String goodsId) {
        Product product = requireProduct(accountId, goodsId);
        MarketingRow row = marketingRow(accountId, goodsId);
        Map<String, Capability> capabilities = capabilities(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("goodsId", goodsId);
        result.put("goodsTitle", product.title());
        result.put("productPrice", product.price());
        result.put("declaredSkuCount", product.declaredSkuCount());
        result.put("rowVersion", row.rowVersion());
        result.put("desired", configurationMap(row.desired()));
        result.put("platformObserved", configurationMap(row.platform()));
        result.put("evidence", Map.of(
                "coverageStatus", row.coverageStatus(),
                "desiredDataSource", row.desiredDataSource(),
                "platformDataSource", row.platformDataSource(),
                "platformSyncedAt", row.platformSyncedAt() == null ? "" : row.platformSyncedAt().toString(),
                "coinAgreementStatus", row.coinAgreementStatus(),
                "coinBalance", row.coinBalance() == null ? "" : row.coinBalance()));
        result.put("capabilities", capabilities.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> capabilityMap(entry.getValue()), (a, b) -> a, LinkedHashMap::new)));
        result.put("qaFixture", qaEligible(accountId));
        result.put("applyAvailable", qaEligible(accountId));
        result.put("executionChannel", qaEligible(accountId) ? "QA_MOCK" : "UNAVAILABLE");
        result.put("platformWriteAvailable", false);
        result.put("platformWriteReason", "尚无经验证的闲鱼商品营销写入适配器；可保存本地方案和执行预检，但不能冒充平台已生效");
        return result;
    }

    public Preview preview(Long accountId, String goodsId, Command command) {
        requireCommand(command, false);
        Product product = requireProduct(accountId, goodsId);
        MarketingRow current = marketingRow(accountId, goodsId);
        requireVersion(command.expectedVersion(), current.rowVersion());
        Configuration desired = validate(command.configuration(), product.price());
        boolean qa = qaEligible(accountId);
        Map<String, Capability> capabilityMap = capabilities(accountId);
        List<String> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!qa) {
            addCapabilityConflict(conflicts, capabilityMap, "FAN_PRICE", hasFanPrice(desired));
            addCapabilityConflict(conflicts, capabilityMap, "BARGAIN_ACTIVITY", Boolean.TRUE.equals(desired.bargainEnabled()));
            addCapabilityConflict(conflicts, capabilityMap, "COIN_DEDUCTION", Boolean.TRUE.equals(desired.coinEnabled()));
            if (Boolean.TRUE.equals(desired.coinEnabled())) {
                if (!"AGREED".equals(current.coinAgreementStatus())) conflicts.add("闲鱼币协议状态未确认，需主账号在闲鱼完成协议确认");
                if (current.coinBalance() == null) conflicts.add("闲鱼币余额尚未同步，无法计算让利可承受范围");
                else {
                    BigDecimal cost = estimatedCoinCost(product.price(), desired.coinDiscountPercent());
                    if (current.coinBalance().compareTo(cost) < 0) conflicts.add("闲鱼币余额不足，预计单件让利 " + cost.toPlainString() + " 元");
                }
            }
            conflicts.add("可靠平台营销写入适配器尚未接入；本次只能保存本地方案，不能提交平台");
        }

        List<String> deletedTiers = deletedFanTiers(current.desired(), desired);
        if (!deletedTiers.isEmpty()) warnings.add("整体覆盖会删除档位：" + String.join("、", deletedTiers));
        if (hasFanPrice(desired)) warnings.add("粉丝价采用整组覆盖；空档位不会写成 0");
        if (Boolean.TRUE.equals(desired.coinEnabled())) {
            warnings.add("闲鱼币抵扣由卖家余额承担，预计单件让利 "
                    + estimatedCoinCost(product.price(), desired.coinDiscountPercent()).toPlainString() + " 元");
        }
        int verifiedSkuCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM xianyu_goods_sku
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, Integer.class, tenant(), accountId, goodsId);
        Integer declaredSkuCount = product.declaredSkuCount();
        String skuCoverageStatus;
        if (declaredSkuCount == null) {
            skuCoverageStatus = "UNKNOWN";
            warnings.add("商品主档 SKU 数尚未同步，影响范围只能按已验证 SKU 计算");
        } else if (declaredSkuCount == verifiedSkuCount) {
            skuCoverageStatus = "FULL";
        } else {
            skuCoverageStatus = "PARTIAL";
            warnings.add("商品主档声明 " + declaredSkuCount + " 个 SKU，但只验证到 " + verifiedSkuCount
                    + " 个；不会把未同步范围显示为 0");
        }
        String channel = qa ? "QA_MOCK" : "UNAVAILABLE";
        String skuScope = declaredSkuCount == null
                ? "已验证 " + verifiedSkuCount + " 个 SKU，主档数量未知"
                : "主档 " + declaredSkuCount + " 个 SKU，已验证 " + verifiedSkuCount + " 个";
        String confirmation = "确认整体覆盖商品 " + goodsId + " 的营销配置（" + skuScope + "），删除 "
                + deletedTiers.size() + " 个粉丝价档位"
                + (qa ? "；隔离 QA Mock，不触达闲鱼平台" : "；当前不可提交平台");
        Map<String, Object> before = configurationMap(current.desired());
        Map<String, Object> after = configurationMap(desired);
        String token = sha256(accountId + "|" + goodsId + "|" + current.rowVersion() + "|" + write(after));
        return new Preview(accountId, goodsId, current.rowVersion(), product.price(), declaredSkuCount,
                verifiedSkuCount, skuCoverageStatus,
                conflicts.isEmpty(), channel, confirmation, token, deletedTiers,
                List.copyOf(warnings), List.copyOf(conflicts), before, after, diff(before, after));
    }

    @Transactional
    public Map<String, Object> saveDraft(Long accountId, String goodsId, Command command) {
        requireCommand(command, false);
        Product product = requireProduct(accountId, goodsId);
        Configuration desired = validate(command.configuration(), product.price());
        String fingerprint = commandFingerprint("MARKETING_DRAFT_CHANGED", command, desired);
        Map<String, Object> replay = replay(accountId, goodsId, "MARKETING_DRAFT_CHANGED", command.requestId(), fingerprint);
        if (replay != null) return replay;
        MarketingRow beforeRow = marketingRow(accountId, goodsId);
        requireVersion(command.expectedVersion(), beforeRow.rowVersion());
        if (!reserveEvent(accountId, goodsId, "MARKETING_DRAFT_CHANGED", command.requestId(), fingerprint,
                configurationMap(beforeRow.desired()), "LOCAL_SUCCESS", "LOCAL")) {
            return requireReplay(accountId, goodsId, "MARKETING_DRAFT_CHANGED", command.requestId(), fingerprint);
        }
        upsertDraft(accountId, goodsId, desired, command.requestId());
        MarketingRow afterRow = marketingRow(accountId, goodsId);
        Map<String, Object> before = configurationMap(beforeRow.desired());
        Map<String, Object> after = configurationMap(afterRow.desired());
        finishEvent(accountId, goodsId, "MARKETING_DRAFT_CHANGED", command.requestId(), after, diff(before, after));
        audit(accountId, goodsId, command.requestId(), "PRODUCT_MARKETING_DRAFT", "保存商品营销方案",
                "LOCAL_SUCCESS", "LOCAL", before, after, diff(before, after));
        Map<String, Object> result = state(accountId, goodsId);
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String, Object> apply(Long accountId, String goodsId, Command command) {
        requireCommand(command, true);
        Configuration desired = validate(command.configuration(), requireProduct(accountId, goodsId).price());
        String fingerprint = commandFingerprint("MARKETING_APPLIED", command, desired);
        Map<String, Object> replay = replay(accountId, goodsId, "MARKETING_APPLIED", command.requestId(), fingerprint);
        if (replay != null) return replay;
        Preview preview = preview(accountId, goodsId, command);
        if (!preview.previewToken().equals(command.previewToken())) throw new BusinessException(409, "营销范围或商品状态已变化，请重新预检");
        if (!preview.confirmationSummary().equals(command.confirmationText())) throw new BusinessException(400, "确认文案不匹配，请重新核对范围");
        if (!preview.executable()) throw new BusinessException(409, String.join("；", preview.conflicts()));
        MarketingRow beforeRow = marketingRow(accountId, goodsId);
        Map<String, Object> before = configurationMap(beforeRow.platform());
        if (!reserveEvent(accountId, goodsId, "MARKETING_APPLIED", command.requestId(), fingerprint,
                before, "QA_CONFIRMED", "QA_FIXTURE")) {
            return requireReplay(accountId, goodsId, "MARKETING_APPLIED", command.requestId(), fingerprint);
        }
        applyQa(accountId, goodsId, desired, command.requestId());
        MarketingRow afterRow = marketingRow(accountId, goodsId);
        Map<String, Object> after = configurationMap(afterRow.platform());
        finishEvent(accountId, goodsId, "MARKETING_APPLIED", command.requestId(), after, diff(before, after));
        audit(accountId, goodsId, command.requestId(), "PRODUCT_MARKETING_APPLY", "应用商品营销配置",
                "QA_CONFIRMED", "QA_FIXTURE", before, after, diff(before, after));
        Map<String, Object> result = state(accountId, goodsId);
        result.put("idempotentReplay", false);
        result.put("executionChannel", "QA_MOCK");
        result.put("platformNetworkCalls", false);
        return result;
    }

    private void upsertDraft(Long accountId, String goodsId, Configuration value, String requestId) {
        jdbc.update("""
                INSERT INTO xianyu_goods_marketing_state
                (tenant_id,xianyu_account_id,xy_goods_id,row_version,
                 desired_fan_all_price,desired_fan_old_price,desired_fan_buyer_price,
                 desired_bargain_enabled,desired_bargain_price,desired_bargain_quantity,
                 desired_coin_enabled,desired_coin_discount_percent,last_request_id,data_source,desired_data_source)
                VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?,'LOCAL_DRAFT','LOCAL_DRAFT')
                ON DUPLICATE KEY UPDATE row_version=row_version+1,
                 desired_fan_all_price=VALUES(desired_fan_all_price),desired_fan_old_price=VALUES(desired_fan_old_price),
                 desired_fan_buyer_price=VALUES(desired_fan_buyer_price),desired_bargain_enabled=VALUES(desired_bargain_enabled),
                 desired_bargain_price=VALUES(desired_bargain_price),desired_bargain_quantity=VALUES(desired_bargain_quantity),
                 desired_coin_enabled=VALUES(desired_coin_enabled),desired_coin_discount_percent=VALUES(desired_coin_discount_percent),
                 last_request_id=VALUES(last_request_id),data_source='LOCAL_DRAFT',desired_data_source='LOCAL_DRAFT'
                """, tenant(), accountId, goodsId, value.fanAllPrice(), value.fanOldPrice(), value.fanBuyerPrice(),
                bool(value.bargainEnabled()), value.bargainPrice(), value.bargainQuantity(), bool(value.coinEnabled()),
                value.coinDiscountPercent(), requestId);
    }

    private void applyQa(Long accountId, String goodsId, Configuration value, String requestId) {
        if (!qaEligible(accountId)) throw new BusinessException(409, "仅隔离 QA 商品允许使用营销 Mock");
        jdbc.update("""
                INSERT INTO xianyu_goods_marketing_state
                (tenant_id,xianyu_account_id,xy_goods_id,row_version,
                 desired_fan_all_price,desired_fan_old_price,desired_fan_buyer_price,
                 desired_bargain_enabled,desired_bargain_price,desired_bargain_quantity,
                 desired_coin_enabled,desired_coin_discount_percent,
                 platform_fan_all_price,platform_fan_old_price,platform_fan_buyer_price,
                 platform_bargain_enabled,platform_bargain_price,platform_bargain_quantity,
                 platform_coin_enabled,platform_coin_discount_percent,coin_agreement_status,
                 coverage_status,data_source,platform_synced_at,last_request_id,desired_data_source,platform_data_source)
                VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'AGREED','FULL','QA_FIXTURE',NOW(3),?,'QA_FIXTURE','QA_FIXTURE')
                ON DUPLICATE KEY UPDATE row_version=row_version+1,
                 desired_fan_all_price=VALUES(desired_fan_all_price),desired_fan_old_price=VALUES(desired_fan_old_price),
                 desired_fan_buyer_price=VALUES(desired_fan_buyer_price),desired_bargain_enabled=VALUES(desired_bargain_enabled),
                 desired_bargain_price=VALUES(desired_bargain_price),desired_bargain_quantity=VALUES(desired_bargain_quantity),
                 desired_coin_enabled=VALUES(desired_coin_enabled),desired_coin_discount_percent=VALUES(desired_coin_discount_percent),
                 platform_fan_all_price=VALUES(platform_fan_all_price),platform_fan_old_price=VALUES(platform_fan_old_price),
                 platform_fan_buyer_price=VALUES(platform_fan_buyer_price),platform_bargain_enabled=VALUES(platform_bargain_enabled),
                 platform_bargain_price=VALUES(platform_bargain_price),platform_bargain_quantity=VALUES(platform_bargain_quantity),
                 platform_coin_enabled=VALUES(platform_coin_enabled),platform_coin_discount_percent=VALUES(platform_coin_discount_percent),
                 coin_agreement_status='AGREED',coverage_status='FULL',data_source='QA_FIXTURE',
                 platform_synced_at=NOW(3),last_request_id=VALUES(last_request_id),
                 desired_data_source='QA_FIXTURE',platform_data_source='QA_FIXTURE'
                """, tenant(), accountId, goodsId,
                value.fanAllPrice(), value.fanOldPrice(), value.fanBuyerPrice(), bool(value.bargainEnabled()),
                value.bargainPrice(), value.bargainQuantity(), bool(value.coinEnabled()), value.coinDiscountPercent(),
                value.fanAllPrice(), value.fanOldPrice(), value.fanBuyerPrice(), bool(value.bargainEnabled()),
                value.bargainPrice(), value.bargainQuantity(), bool(value.coinEnabled()), value.coinDiscountPercent(), requestId);
    }

    private Product requireProduct(Long accountId, String goodsId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        if (goodsId == null || goodsId.isBlank() || goodsId.length() > 100) throw new BusinessException(400, "商品ID无效");
        accountAccess.requireAccess(accountId);
        List<Product> rows = jdbc.query("""
                SELECT title,sold_price,sku_count FROM xianyu_goods
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_good_id=?
                """, (rs, row) -> new Product(rs.getString("title"), rs.getBigDecimal("sold_price"),
                        nullableInteger(rs, "sku_count")),
                tenant(), accountId, goodsId.trim());
        if (rows.isEmpty()) throw new BusinessException(404, "商品不存在或不属于当前经营主体");
        return rows.getFirst();
    }

    private MarketingRow marketingRow(Long accountId, String goodsId) {
        List<MarketingRow> rows = jdbc.query("""
                SELECT * FROM xianyu_goods_marketing_state
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=?
                """, (rs, row) -> row(rs), tenant(), accountId, goodsId);
        return rows.isEmpty() ? MarketingRow.empty() : rows.getFirst();
    }

    private MarketingRow row(ResultSet rs) throws SQLException {
        Configuration desired = new Configuration(
                rs.getBigDecimal("desired_fan_all_price"), rs.getBigDecimal("desired_fan_old_price"),
                rs.getBigDecimal("desired_fan_buyer_price"), nullableBoolean(rs, "desired_bargain_enabled"),
                rs.getBigDecimal("desired_bargain_price"), nullableInteger(rs, "desired_bargain_quantity"),
                nullableBoolean(rs, "desired_coin_enabled"), nullableInteger(rs, "desired_coin_discount_percent"));
        Configuration platform = new Configuration(
                rs.getBigDecimal("platform_fan_all_price"), rs.getBigDecimal("platform_fan_old_price"),
                rs.getBigDecimal("platform_fan_buyer_price"), nullableBoolean(rs, "platform_bargain_enabled"),
                rs.getBigDecimal("platform_bargain_price"), nullableInteger(rs, "platform_bargain_quantity"),
                nullableBoolean(rs, "platform_coin_enabled"), nullableInteger(rs, "platform_coin_discount_percent"));
        return new MarketingRow(rs.getLong("row_version"), desired, platform,
                rs.getString("coin_agreement_status"), rs.getBigDecimal("coin_balance"),
                rs.getString("coverage_status"), rs.getString("data_source"),
                rs.getString("desired_data_source"), rs.getString("platform_data_source"),
                rs.getTimestamp("platform_synced_at") == null ? null : rs.getTimestamp("platform_synced_at").toLocalDateTime());
    }

    private Map<String, Capability> capabilities(Long accountId) {
        Map<String, Capability> result = new LinkedHashMap<>();
        for (String code : MARKETING_CAPABILITIES) result.put(code, new Capability("UNKNOWN", "NONE", "尚未探测"));
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT capability_code,status,source,detail FROM xianyu_account_capability
                 WHERE tenant_id=? AND xianyu_account_id=?
                   AND capability_code IN ('FAN_PRICE','BARGAIN_ACTIVITY','COIN_DEDUCTION','PAID_PROMOTION')
                """, tenant(), accountId)) {
            result.put(String.valueOf(row.get("capability_code")), new Capability(
                    String.valueOf(row.get("status")), String.valueOf(row.get("source")), String.valueOf(row.get("detail"))));
        }
        if (qaEligible(accountId)) {
            for (String code : List.of("FAN_PRICE", "BARGAIN_ACTIVITY", "COIN_DEDUCTION")) {
                result.put(code, new Capability("QA_MOCK", "QA_FIXTURE",
                        "仅验证本地营销状态机，不会向闲鱼发送平台请求"));
            }
        }
        result.put("PAID_PROMOTION", new Capability("UNAVAILABLE", "LOCAL_GUARD",
                "尚无可靠的推广、曝光归因或佣金平台适配器"));
        return result;
    }

    Configuration validate(Configuration input, BigDecimal productPrice) {
        if (input == null) throw new BusinessException(400, "营销配置不能为空");
        if (productPrice == null || productPrice.signum() <= 0) throw new BusinessException(409, "商品售价尚未同步，无法校验营销价格");
        BigDecimal all = marketingPrice(input.fanAllPrice(), productPrice, "全部粉丝价");
        BigDecimal old = marketingPrice(input.fanOldPrice(), productPrice, "老粉价");
        BigDecimal buyer = marketingPrice(input.fanBuyerPrice(), productPrice, "已购粉价");
        Boolean bargainEnabled = input.bargainEnabled() == null ? false : input.bargainEnabled();
        BigDecimal bargainPrice = bargainEnabled ? marketingPriceRequired(input.bargainPrice(), productPrice, "小刀活动价") : null;
        Integer bargainQuantity = bargainEnabled ? range(input.bargainQuantity(), 1, 9999, "小刀份数") : null;
        Boolean coinEnabled = input.coinEnabled() == null ? false : input.coinEnabled();
        Integer coinPercent = coinEnabled ? range(input.coinDiscountPercent(), 1, 99, "闲鱼币抵扣比例") : null;
        return new Configuration(all, old, buyer, bargainEnabled, bargainPrice, bargainQuantity, coinEnabled, coinPercent);
    }

    private BigDecimal marketingPrice(BigDecimal value, BigDecimal productPrice, String label) {
        if (value == null) return null;
        return marketingPriceRequired(value, productPrice, label);
    }

    private BigDecimal marketingPriceRequired(BigDecimal value, BigDecimal productPrice, String label) {
        if (value == null) throw new BusinessException(400, label + "不能为空");
        if (value.scale() > 2) throw new BusinessException(400, label + "最多保留两位小数");
        if (value.signum() <= 0) throw new BusinessException(400, label + "必须大于 0");
        if (value.compareTo(productPrice) >= 0) throw new BusinessException(400, label + "必须严格低于商品原价 " + productPrice.toPlainString());
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private int range(Integer value, int min, int max, String label) {
        if (value == null || value < min || value > max) throw new BusinessException(400, label + "必须在 " + min + "～" + max + " 之间");
        return value;
    }

    private void requireCommand(Command command, boolean applying) {
        if (command == null) throw new BusinessException(400, "营销请求不能为空");
        if (command.requestId() == null || command.requestId().isBlank() || command.requestId().length() > 80) {
            throw new BusinessException(400, "requestId 不能为空且最多 80 字符");
        }
        if (applying && (command.previewToken() == null || command.previewToken().isBlank())) {
            throw new BusinessException(400, "请先完成营销预检");
        }
    }

    private void requireVersion(Long expected, long actual) {
        if (expected == null || expected != actual) throw new BusinessException(409, "营销配置已变化，请刷新后重试");
    }

    private boolean reserveEvent(Long accountId, String goodsId, String eventType, String requestId,
                                 String fingerprint, Object before, String outcome, String source) {
        return jdbc.update("""
                INSERT IGNORE INTO xianyu_goods_event
                (tenant_id,xianyu_account_id,xy_goods_id,event_type,event_origin,outcome_state,data_source,
                 operator_user_id,operator_username,request_id,idempotency_key,before_json)
                VALUES (?,?,?,?,'USER',?,?,?,?,?,?,?)
                """, tenant(), accountId, goodsId, eventType, outcome, source,
                UserContext.getUserId(), UserContext.getUsername(), requestId, fingerprint, write(before)) == 1;
    }

    private void finishEvent(Long accountId, String goodsId, String eventType, String requestId,
                             Object after, Object fieldDiff) {
        jdbc.update("""
                UPDATE xianyu_goods_event SET after_json=?,field_diff_json=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND event_type=? AND request_id=?
                """, write(after), write(fieldDiff), tenant(), accountId, goodsId, eventType, requestId);
    }

    private Map<String, Object> replay(Long accountId, String goodsId, String eventType,
                                       String requestId, String fingerprint) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT idempotency_key FROM xianyu_goods_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND xy_goods_id=? AND event_type=? AND request_id=?
                """, tenant(), accountId, goodsId, eventType, requestId);
        if (rows.isEmpty()) return null;
        if (!fingerprint.equals(String.valueOf(rows.getFirst().get("idempotency_key")))) {
            throw new BusinessException(409, "requestId 已用于不同的营销配置");
        }
        Map<String, Object> result = state(accountId, goodsId);
        result.put("idempotentReplay", true);
        return result;
    }

    private Map<String, Object> requireReplay(Long accountId, String goodsId, String eventType,
                                              String requestId, String fingerprint) {
        Map<String, Object> result = replay(accountId, goodsId, eventType, requestId, fingerprint);
        if (result == null) throw new BusinessException(409, "并发营销请求尚未完成，请稍后按同 requestId 查询");
        return result;
    }

    private void audit(Long accountId, String goodsId, String requestId, String type, String description,
                       String outcome, String source, Object before, Object after, Object fieldDiff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setOperationType(OperationConstants.Type.UPDATE);
        log.setOperationModule(OperationConstants.Module.GOODS);
        log.setOperationDesc(description);
        log.setOperationStatus(OperationConstants.Status.SUCCESS);
        log.setTargetType(OperationConstants.TargetType.GOODS);
        log.setTargetId(goodsId);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource(source);
        log.setRequestParams(write(Map.of("before", before, "after", after, "fieldDiff", fieldDiff)));
        log.setResponseResult(write(Map.of("accountId", accountId, "goodsId", goodsId, "outcomeState", outcome)));
        operationLogs.logRequired(log);
    }

    private Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, String> labels = Map.of(
                "fanAllPrice", "全部粉丝价", "fanOldPrice", "老粉价", "fanBuyerPrice", "已购粉价",
                "bargainEnabled", "小刀活动", "bargainPrice", "小刀活动价", "bargainQuantity", "小刀份数",
                "coinEnabled", "闲鱼币抵扣", "coinDiscountPercent", "闲鱼币抵扣比例");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : before.keySet()) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                result.put(field, Map.of("label", labels.get(field), "before", safe(before.get(field)), "after", safe(after.get(field))));
            }
        }
        return result;
    }

    private List<String> deletedFanTiers(Configuration before, Configuration after) {
        List<String> result = new ArrayList<>();
        if (before.fanAllPrice() != null && after.fanAllPrice() == null) result.add("全部粉丝价");
        if (before.fanOldPrice() != null && after.fanOldPrice() == null) result.add("老粉价");
        if (before.fanBuyerPrice() != null && after.fanBuyerPrice() == null) result.add("已购粉价");
        return result;
    }

    private void addCapabilityConflict(List<String> conflicts, Map<String, Capability> capabilities,
                                       String code, boolean requested) {
        if (!requested) return;
        Capability capability = capabilities.get(code);
        if (capability == null || !"READY".equals(capability.status())) {
            conflicts.add(code + " 不可用：" + (capability == null ? "尚未探测" : capability.detail()));
        }
    }

    private String commandFingerprint(String event, Command command, Configuration configuration) {
        return sha256(event + "|" + command.expectedVersion() + "|" + write(configurationMap(configuration))
                + "|" + safe(command.previewToken()) + "|" + safe(command.confirmationText()));
    }

    private Map<String, Object> configurationMap(Configuration value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fanAllPrice", value.fanAllPrice());
        result.put("fanOldPrice", value.fanOldPrice());
        result.put("fanBuyerPrice", value.fanBuyerPrice());
        result.put("bargainEnabled", value.bargainEnabled());
        result.put("bargainPrice", value.bargainPrice());
        result.put("bargainQuantity", value.bargainQuantity());
        result.put("coinEnabled", value.coinEnabled());
        result.put("coinDiscountPercent", value.coinDiscountPercent());
        return result;
    }

    private Map<String, Object> capabilityMap(Capability value) {
        return Map.of("status", value.status(), "source", value.source(), "detail", value.detail());
    }

    private BigDecimal estimatedCoinCost(BigDecimal price, Integer percent) {
        if (price == null || percent == null) return BigDecimal.ZERO.setScale(2);
        return price.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private boolean hasFanPrice(Configuration value) {
        return value.fanAllPrice() != null || value.fanOldPrice() != null || value.fanBuyerPrice() != null;
    }

    private boolean qaEligible(Long accountId) {
        return qaMockEnabled && tenant() == qaTenantId && qaAccountIds.contains(accountId);
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "租户上下文缺失");
        return tenantId;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("营销数据序列化失败", error); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static Object safe(Object value) { return value == null ? "" : value; }
    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException { return (Integer) rs.getObject(column); }
    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue() == 1;
    }
    private static Integer bool(Boolean value) { return value == null ? null : value ? 1 : 0; }
    private static Set<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        try { return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isBlank()).map(Long::valueOf).collect(Collectors.toUnmodifiableSet()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid QA marketing account list", error); }
    }

    public record Configuration(BigDecimal fanAllPrice, BigDecimal fanOldPrice, BigDecimal fanBuyerPrice,
                                Boolean bargainEnabled, BigDecimal bargainPrice, Integer bargainQuantity,
                                Boolean coinEnabled, Integer coinDiscountPercent) { }
    public record Command(String requestId, Long expectedVersion, Configuration configuration,
                          String previewToken, String confirmationText) { }
    public record Preview(Long accountId, String goodsId, long rowVersion, BigDecimal productPrice,
                          Integer declaredSkuCount, int verifiedSkuCount, String skuCoverageStatus,
                          boolean executable, String executionChannel,
                          String confirmationSummary, String previewToken, List<String> deletedFanTiers,
                          List<String> warnings, List<String> conflicts, Map<String, Object> before,
                          Map<String, Object> after, Map<String, Object> fieldDiff) { }

    private record Product(String title, BigDecimal price, Integer declaredSkuCount) { }
    private record Capability(String status, String source, String detail) { }
    private record MarketingRow(long rowVersion, Configuration desired, Configuration platform,
                                String coinAgreementStatus, BigDecimal coinBalance, String coverageStatus,
                                String dataSource, String desiredDataSource, String platformDataSource,
                                LocalDateTime platformSyncedAt) {
        private static MarketingRow empty() {
            Configuration empty = new Configuration(null, null, null, null, null, null, null, null);
            return new MarketingRow(0, empty, empty, "UNKNOWN", null, "UNSYNCED", "NONE",
                    "NONE", "NONE", null);
        }
    }
}
