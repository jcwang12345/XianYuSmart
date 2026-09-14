package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 商品发布结构化草稿与本地参考目录。平台真值在预检阶段单独核对。 */
@Service
public class ListingDraftService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccountAccessService accountAccessService;
    private final PublishCapabilityService capabilityService;

    public ListingDraftService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               AccountAccessService accountAccessService,
                               PublishCapabilityService capabilityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accountAccessService = accountAccessService;
        this.capabilityService = capabilityService;
    }

    public Map<String, Object> formSchema(Long accountId, String listingType) {
        accountAccessService.requireAccess(accountId);
        String type = normalizeType(listingType);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("listingType", type);
        response.put("source", "LOCAL_REFERENCE");
        response.put("verificationStatus", "PENDING_PLATFORM_PREFLIGHT");
        response.put("limits", Map.of(
                "title", 120, "description", 3000, "images", 9,
                "skuDimensions", 2, "skuCombinations", 50));
        response.put("businessModes", List.of(
                option("NORMAL", "普通闲置", "适用于个人号常规商品"),
                option("FISH_SHOP", "鱼小铺商品", "仅在店铺已开通且通道探测通过时可发布"),
                option("AUTHORIZED", "官方授权商品", "需要开放平台授权和类目权限")));
        response.put("conditions", type.equals("PHYSICAL") ? List.of(
                option("NEW", "全新", "未拆封或未使用"),
                option("LIKE_NEW", "几乎全新", "轻微使用痕迹"),
                option("GOOD", "成色良好", "正常使用痕迹"),
                option("FAIR", "明显使用痕迹", "需在描述中如实说明")) : List.of(
                option("DIGITAL", "数字交付", "以授权、卡密或在线服务交付")));
        response.put("industries", type.equals("PHYSICAL") ? physicalIndustries() : virtualIndustries());
        response.put("shippingModes", type.equals("PHYSICAL") ? List.of(
                option("FREE_SHIPPING", "卖家包邮", "运费由卖家承担"),
                option("FREIGHT_TEMPLATE", "运费模板", "使用平台已配置的模板"),
                option("SELF_PICKUP", "当面交易/自提", "买卖双方线下交付")) : List.of(
                option("ONLINE_DELIVERY", "线上交付", "适合软件、卡密与数字服务"),
                option("FACE_TO_FACE", "当面交易", "需要双方线下确认")));
        response.put("serviceProtocols", List.of(
                protocol("FAST_DELIVERY_24_HOUR", "24 小时发货", "需平台资格", "NOT_VERIFIED"),
                protocol("FAST_DELIVERY_48_HOUR", "48 小时发货", "需平台资格", "NOT_VERIFIED"),
                protocol("SEVEN_DAY_RETURN", "七天退货", "实物商品需核对类目与协议", "NOT_VERIFIED"),
                protocol("VIRTUAL_SUPPORT", "虚拟商品售后保障", "按实际服务能力承诺", "LOCAL_ONLY")));
        response.put("channelCapabilities", capabilityService.capabilities(accountId));
        response.put("notice", "类目和属性来自本地参考目录；平台最终类目、协议和可发布性以预检回读为准。");
        return response;
    }

    public List<Map<String, Object>> list(Long accountId) {
        accountAccessService.requireAccess(accountId);
        return jdbcTemplate.query("""
                SELECT id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                       payload_json,data_source,operator_username,created_time,updated_time
                  FROM xianyu_listing_draft
                 WHERE tenant_id=? AND xianyu_account_id=?
                 ORDER BY updated_time DESC,id DESC LIMIT 100
                """, (rs, rowNum) -> row(rs), tenant(), accountId);
    }

    public Map<String, Object> get(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                       payload_json,data_source,operator_username,created_time,updated_time
                  FROM xianyu_listing_draft WHERE tenant_id=? AND id=?
                """, (rs, rowNum) -> row(rs), tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "商品草稿不存在");
        Long accountId = ((Number) rows.get(0).get("accountId")).longValue();
        accountAccessService.requireAccess(accountId);
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_listing_draft
                (tenant_id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                 payload_json,data_source,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,'DRAFT',1,?,'LOCAL_DRAFT',?,?)
                """, tenant(), accountId, draftName(payload), normalizeType(text(payload.get("productType"))),
                blank(text(payload.get("publishChannel"))), json(payload),
                UserContext.getUserId(), UserContext.getUsername());
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> request) {
        Map<String, Object> current = get(id);
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        int expectedRevision = intValue(request.get("revision"), intValue(current.get("revision"), 1));
        int changed = jdbcTemplate.update("""
                UPDATE xianyu_listing_draft
                   SET xianyu_account_id=?,draft_name=?,listing_type=?,publish_channel=?,
                       payload_json=?,revision=revision+1,operator_user_id=?,operator_username=?
                 WHERE tenant_id=? AND id=? AND revision=? AND status='DRAFT'
                """, accountId, draftName(payload), normalizeType(text(payload.get("productType"))),
                blank(text(payload.get("publishChannel"))), json(payload), UserContext.getUserId(),
                UserContext.getUsername(), tenant(), id, expectedRevision);
        if (changed == 0) throw new BusinessException(409, "草稿已被其他页面更新，请刷新后合并修改");
        return get(id);
    }

    public Map<String, Object> validate(Map<String, Object> request) {
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        Validation result = validatePayload(payload);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", result.errors().isEmpty());
        response.put("errors", result.errors());
        response.put("warnings", result.warnings());
        response.put("preview", preview(payload));
        response.put("fieldReadiness", fieldReadiness(payload));
        response.put("source", "LOCAL_DRAFT");
        return response;
    }

    /** 发布预检必须先通过结构校验；草稿本身允许不完整保存。 */
    public Map<String, Object> requireValidForPublish(Map<String, Object> request) {
        Map<String, Object> result = validate(request);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        if (!errors.isEmpty()) throw new BusinessException(400, errors.get(0));
        return result;
    }

    /**
     * 真实提交不得静默丢弃工作台高级字段。QA_LOCAL 使用持久化隔离适配器，可覆盖完整状态流程；
     * 其他通道只有明确返回能力证据时才允许提交对应字段。
     */
    public Map<String, Object> requireExecutable(Map<String, Object> request) {
        Map<String, Object> validation = requireValidForPublish(request);
        Map<String, Object> payload = payload(request);
        String channelCode = text(payload.get("publishChannel")).toUpperCase(Locale.ROOT);
        if ("QA_LOCAL".equals(channelCode)) return validation;

        Long accountId = number(payload.get("xianyuAccountId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilityService.capabilities(accountId).get("channels");
        Map<String, Object> channel = channels.stream()
                .filter(item -> channelCode.equals(text(item.get("channelCode"))))
                .findFirst().orElse(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> features = channel.get("features") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        List<String> unsupported = new ArrayList<>();
        List<?> skus = payload.get("skus") instanceof List<?> value ? value : List.of();
        if (!skus.isEmpty() && !supported(features.get("sku"))) unsupported.add("多规格 SKU");
        if (!text(payload.get("videoUrl")).isBlank()) unsupported.add("商品视频");
        if (payload.get("originalPrice") != null && !text(payload.get("originalPrice")).isBlank()) unsupported.add("原价/划线价");
        if (!text(payload.get("outerId")).isBlank()) unsupported.add("商家编码");
        if (!text(payload.get("leafCategoryCode")).isBlank()) unsupported.add("指定叶子类目");
        if (payload.get("categoryAttributes") instanceof Map<?, ?> value && !value.isEmpty()) unsupported.add("类目属性");
        if (payload.get("serviceProtocols") instanceof List<?> value && !value.isEmpty()) unsupported.add("服务协议");
        if (!"NORMAL".equals(text(payload.get("businessMode")))) unsupported.add("鱼小铺/官方授权身份");
        if ("PHYSICAL".equals(normalizeType(text(payload.get("productType"))))
                && !text(payload.get("conditionCode")).isBlank()) unsupported.add("实物成色");
        if (!unsupported.isEmpty()) {
            throw new BusinessException(409, "当前发布通道尚未验证以下字段，已阻止静默丢失：" + String.join("、", unsupported));
        }
        return validation;
    }

    static Validation validatePayload(Map<String, Object> payload) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String type = normalizeType(text(payload.get("productType")));
        String title = text(payload.get("name"));
        String description = text(payload.get("description"));
        if (title.isBlank()) errors.add("商品标题不能为空");
        if (title.length() > 120) errors.add("商品标题不能超过120个字符");
        if (description.isBlank()) errors.add("商品详情不能为空");
        if (description.length() > 3000) errors.add("商品详情不能超过3000个字符");
        BigDecimal price = decimal(payload.get("amount"));
        BigDecimal original = decimal(payload.get("originalPrice"));
        if (price == null || price.signum() <= 0 || price.stripTrailingZeros().scale() > 2) {
            errors.add("售价必须大于0且最多保留两位小数");
        }
        if (original != null && price != null && original.compareTo(price) < 0) {
            errors.add("原价不能低于售价");
        }
        int stock = intValue(payload.get("stock"), 0);
        if (stock < 1) errors.add("库存必须为正整数");
        List<?> images = payload.get("images") instanceof List<?> list ? list : List.of();
        if (images.isEmpty() || images.size() > 9) errors.add("商品图片必须为1至9张");
        String shipping = text(payload.get("shippingMode"));
        if ("PHYSICAL".equals(type) && "ONLINE_DELIVERY".equals(shipping)) errors.add("实物商品不能使用线上交付");
        if ("VIRTUAL".equals(type) && ("FREE_SHIPPING".equals(shipping) || "FREIGHT_TEMPLATE".equals(shipping))) {
            errors.add("虚拟商品不能使用快递运费方式");
        }
        if ("FREIGHT_TEMPLATE".equals(shipping) && text(payload.get("freightTemplateId")).isBlank()) {
            errors.add("使用运费模板时必须填写模板ID");
        }
        if (text(payload.get("leafCategoryCode")).isBlank()) warnings.add("尚未选择叶子类目，平台预检可能改写类目");
        List<?> dimensions = payload.get("skuDimensions") instanceof List<?> list ? list : List.of();
        List<?> skus = payload.get("skus") instanceof List<?> list ? list : List.of();
        if (dimensions.size() > 2) errors.add("SKU最多支持2个规格维度");
        if (skus.size() > 50) errors.add("SKU组合不能超过50个");
        if (!skus.isEmpty()) warnings.add("多SKU已保存到草稿；仅通道能力验证通过后才会提交平台");
        if (!text(payload.get("videoUrl")).isBlank()) warnings.add("视频已保存到草稿；当前真实发布适配器尚未验证视频提交");
        return new Validation(errors, warnings);
    }

    private Map<String, Object> preview(Map<String, Object> payload) {
        Map<String, Object> preview = new LinkedHashMap<>();
        for (String key : List.of("name", "description", "amount", "originalPrice", "stock", "productType",
                "conditionCode", "businessMode", "industryCode", "leafCategoryCode", "leafCategoryName",
                "shippingMode", "shippingFee", "province", "city", "district", "images", "skus",
                "serviceProtocols", "outerId")) preview.put(key, payload.get(key));
        return preview;
    }

    private Map<String, Object> fieldReadiness(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("productType", "name", "description", "amount", "stock", "images", "shippingMode")) {
            result.put(key, "SUPPORTED_BASELINE");
        }
        for (String key : List.of("originalPrice", "conditionCode", "outerId", "industryCode", "leafCategoryCode",
                "categoryAttributes", "skus", "serviceProtocols", "videoUrl")) {
            result.put(key, payload.containsKey(key) ? "PENDING_CHANNEL_VERIFICATION" : "NOT_CONFIGURED");
        }
        return result;
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getLong("id"));
        result.put("accountId", rs.getLong("xianyu_account_id"));
        result.put("draftName", rs.getString("draft_name"));
        result.put("listingType", rs.getString("listing_type"));
        result.put("publishChannel", rs.getString("publish_channel"));
        result.put("status", rs.getString("status"));
        result.put("revision", rs.getInt("revision"));
        result.put("payload", readJson(rs.getString("payload_json")));
        result.put("dataSource", rs.getString("data_source"));
        result.put("operatorUsername", rs.getString("operator_username"));
        result.put("createdTime", instant(rs.getTimestamp("created_time")));
        result.put("updatedTime", instant(rs.getTimestamp("updated_time")));
        return result;
    }

    private Map<String, Object> payload(Map<String, Object> request) {
        Object nested = request.get("payload");
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return new LinkedHashMap<>(request);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(400, "商品草稿无法序列化", e); }
    }

    private Map<String, Object> readJson(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception e) { return Map.of(); }
    }

    private String draftName(Map<String, Object> payload) {
        String value = text(payload.get("name"));
        return value.isBlank() ? "未命名商品草稿" : value.substring(0, Math.min(value.length(), 200));
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private static Long number(Object value) {
        try { return Long.valueOf(String.valueOf(value)); }
        catch (Exception e) { throw new BusinessException(400, "请选择发布账号"); }
    }

    private static String normalizeType(String value) {
        return "PHYSICAL".equalsIgnoreCase(value) ? "PHYSICAL" : "VIRTUAL";
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static int intValue(Object value, int fallback) {
        try { return new BigDecimal(String.valueOf(value)).intValueExact(); }
        catch (Exception e) { return fallback; }
    }
    private static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static boolean supported(Object value) {
        String status = text(value).toUpperCase(Locale.ROOT);
        return "READY".equals(status) || "SUPPORTED".equals(status);
    }

    private static Map<String, Object> option(String value, String label, String description) {
        return Map.of("value", value, "label", label, "description", description);
    }
    private static Map<String, Object> protocol(String code, String label, String description, String status) {
        return Map.of("code", code, "label", label, "description", description, "status", status);
    }
    private static List<Map<String, Object>> virtualIndustries() {
        return List.of(
                industry("SOFTWARE", "软件与数字工具", List.of(
                        leaf("OFFICE_PLUGIN", "办公软件与插件", List.of(attribute("softwarePlatform", "适用平台", true, List.of("Office", "WPS", "Office + WPS")), attribute("licenseDays", "授权有效期", true, List.of("7天", "30天", "永久")))),
                        leaf("UTILITY_SOFTWARE", "工具软件", List.of(attribute("operatingSystem", "操作系统", true, List.of("Windows", "macOS", "多平台")))))),
                industry("DIGITAL_SERVICE", "数字服务", List.of(
                        leaf("ONLINE_TUTORIAL", "在线教程与指导", List.of(attribute("deliveryFormat", "交付形式", true, List.of("视频", "文档", "社群指导")))),
                        leaf("VIRTUAL_CARD", "虚拟卡券", List.of(attribute("validity", "有效期", true, List.of("即时生效", "7天", "30天"))))))
        );
    }
    private static List<Map<String, Object>> physicalIndustries() {
        Map<String, Object> computerAccessory = leaf("COMPUTER_ACCESSORY", "电脑配件", List.of(
                attribute("brand", "品牌", false, List.of()),
                attribute("model", "型号", false, List.of())));
        Map<String, Object> officeDevice = leaf("OFFICE_DEVICE", "办公设备", List.of(
                attribute("brand", "品牌", false, List.of()),
                attribute("warranty", "保修情况", false, List.of("无保修", "店保", "官方保修"))));
        Map<String, Object> writingSupply = leaf("WRITING_SUPPLY", "书写工具", List.of(
                attribute("brand", "品牌", false, List.of()),
                attribute("specification", "规格型号", false, List.of())));
        Map<String, Object> paperSupply = leaf("PAPER_SUPPLY", "纸品耗材", List.of(
                attribute("size", "尺寸", false, List.of("A4", "A5", "其他"))));
        return List.of(
                industry("DIGITAL_DEVICE", "数码与电脑", List.of(computerAccessory, officeDevice)),
                industry("OFFICE_SUPPLY", "办公与文具", List.of(writingSupply, paperSupply)));
    }
    private static Map<String, Object> industry(String code, String name, List<Map<String, Object>> leaves) {
        return Map.of("code", code, "name", name, "leafCategories", leaves);
    }
    private static Map<String, Object> leaf(String code, String name, List<Map<String, Object>> attributes) {
        return Map.of("code", code, "name", name, "source", "LOCAL_REFERENCE", "attributes", attributes);
    }
    private static Map<String, Object> attribute(String code, String name, boolean required, List<String> options) {
        return Map.of("code", code, "name", name, "required", required, "options", options);
    }

    record Validation(List<String> errors, List<String> warnings) { }
}
