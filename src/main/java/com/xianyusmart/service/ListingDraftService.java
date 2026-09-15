package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 商品发布结构化草稿与本地参考目录。平台真值在预检阶段单独核对。 */
@Service
public class ListingDraftService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccountAccessService accountAccessService;
    private final PublishCapabilityService capabilityService;
    private final ListingCatalogService catalogService;
    private final OperationLogService operationLogService;
    private final ProductContentPolicyService contentPolicyService;

    public ListingDraftService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               AccountAccessService accountAccessService,
                               PublishCapabilityService capabilityService,
                               ListingCatalogService catalogService,
                               OperationLogService operationLogService,
                               ProductContentPolicyService contentPolicyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accountAccessService = accountAccessService;
        this.capabilityService = capabilityService;
        this.catalogService = catalogService;
        this.operationLogService = operationLogService;
        this.contentPolicyService = contentPolicyService;
    }

    public Map<String, Object> formSchema(Long accountId, String listingType) {
        accountAccessService.requireAccess(accountId);
        String type = normalizeType(listingType);
        ListingCatalogService.Catalog catalog = catalogService.active(type);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("listingType", type);
        response.put("source", catalog.source());
        response.put("catalogVersion", catalog.version());
        response.put("verificationStatus", catalog.verificationStatus());
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
                option("FAIR", "明显使用痕迹", "需在描述中如实说明")) : type.equals("SERVICE") ? List.of(
                option("SERVICE", "服务交付", "按约定时段远程、到店或上门履约")) : List.of(
                option("DIGITAL", "数字交付", "以授权、卡密或在线内容交付")));
        response.put("industries", catalog.industries());
        response.put("shippingModes", type.equals("PHYSICAL") ? List.of(
                option("FREE_SHIPPING", "卖家包邮", "运费由卖家承担"),
                option("FREIGHT_TEMPLATE", "运费模板", "使用平台已配置的模板"),
                option("SELF_PICKUP", "当面交易/自提", "买卖双方线下交付")) : type.equals("SERVICE") ? List.of(
                option("REMOTE_SERVICE", "远程服务", "在线预约后远程履约"),
                option("ON_SITE_SERVICE", "上门服务", "按服务范围预约上门"),
                option("STORE_SERVICE", "到店服务", "买家预约后到店履约")) : List.of(
                option("ONLINE_DELIVERY", "线上交付", "适合软件、卡密与数字内容"),
                option("FACE_TO_FACE", "当面交易", "需要双方线下确认")));
        response.put("typeRequirements", typeRequirements(type));
        response.put("serviceProtocols", List.of(
                protocol("FAST_DELIVERY_24_HOUR", "24 小时发货", "需平台资格", "NOT_VERIFIED"),
                protocol("FAST_DELIVERY_48_HOUR", "48 小时发货", "需平台资格", "NOT_VERIFIED"),
                protocol("SEVEN_DAY_RETURN", "七天退货", "实物商品需核对类目与协议", "NOT_VERIFIED"),
                protocol("VIRTUAL_SUPPORT", "虚拟商品售后保障", "按实际服务能力承诺", "LOCAL_ONLY")));
        response.put("channelCapabilities", capabilityService.capabilities(accountId));
        response.put("notice", "目录版本 " + catalog.version() + " 来自本地参考目录；平台最终类目、协议和可发布性以预检回读为准。");
        return response;
    }

    public List<Map<String, Object>> list(Long accountId) {
        accountAccessService.requireAccess(accountId);
        return jdbcTemplate.query("""
                SELECT id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                       catalog_version,payload_fingerprint,payload_json,data_source,operator_username,created_time,updated_time
                  FROM xianyu_listing_draft
                 WHERE tenant_id=? AND xianyu_account_id=?
                 ORDER BY updated_time DESC,id DESC LIMIT 100
                """, (rs, rowNum) -> row(rs), tenant(), accountId);
    }

    public Map<String, Object> get(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                       catalog_version,payload_fingerprint,payload_json,data_source,operator_username,created_time,updated_time
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
        String catalogVersion = requireCurrentCatalog(payload);
        payload.put("catalogVersion", catalogVersion);
        String fingerprint = fingerprint(payload);
        jdbcTemplate.update("""
                INSERT INTO xianyu_listing_draft
                (tenant_id,xianyu_account_id,draft_name,listing_type,publish_channel,status,revision,
                 catalog_version,payload_fingerprint,payload_json,data_source,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,'DRAFT',1,?,?,?,'LOCAL_DRAFT',?,?)
                """, tenant(), accountId, draftName(payload), normalizeType(text(payload.get("productType"))),
                blank(text(payload.get("publishChannel"))), catalogVersion, fingerprint, json(payload),
                UserContext.getUserId(), UserContext.getUsername());
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertVersion(id, 1, changeSource(request), catalogVersion, fingerprint, payload);
        Map<String, Object> result = get(id);
        auditDraft(accountId, id, "LISTING_DRAFT_CREATE", requestId(request), null, result, payload);
        return result;
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> request) {
        Map<String, Object> current = get(id);
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        String catalogVersion = requireCurrentCatalog(payload);
        payload.put("catalogVersion", catalogVersion);
        String fingerprint = fingerprint(payload);
        int expectedRevision = intValue(request.get("revision"), intValue(current.get("revision"), 1));
        int changed = jdbcTemplate.update("""
                UPDATE xianyu_listing_draft
                   SET xianyu_account_id=?,draft_name=?,listing_type=?,publish_channel=?,
                       catalog_version=?,payload_fingerprint=?,payload_json=?,revision=revision+1,
                       operator_user_id=?,operator_username=?
                 WHERE tenant_id=? AND id=? AND revision=? AND status='DRAFT'
                """, accountId, draftName(payload), normalizeType(text(payload.get("productType"))),
                blank(text(payload.get("publishChannel"))), catalogVersion, fingerprint, json(payload), UserContext.getUserId(),
                UserContext.getUsername(), tenant(), id, expectedRevision);
        if (changed == 0) throw new BusinessException(409, "草稿已被其他页面更新，请刷新后合并修改");
        Map<String, Object> result = get(id);
        int revision = intValue(result.get("revision"), expectedRevision + 1);
        insertVersion(id, revision, changeSource(request), catalogVersion, fingerprint, payload);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("beforeFingerprint", current.get("payloadFingerprint"));
        diff.put("afterFingerprint", fingerprint);
        diff.put("beforeRevision", expectedRevision);
        diff.put("afterRevision", revision);
        auditDraft(accountId, id, "LISTING_DRAFT_UPDATE", requestId(request), current, result, diff);
        return result;
    }

    public List<Map<String, Object>> versions(Long id) {
        Map<String, Object> draft = get(id);
        Long accountId = ((Number) draft.get("accountId")).longValue();
        accountAccessService.requireAccess(accountId);
        return jdbcTemplate.query("""
                SELECT revision,change_source,catalog_version,payload_fingerprint,payload_json,
                       operator_username,created_time
                  FROM xianyu_listing_draft_version
                 WHERE tenant_id=? AND draft_id=? ORDER BY revision DESC
                """, (rs, rowNum) -> {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("revision", rs.getInt("revision"));
            version.put("changeSource", rs.getString("change_source"));
            version.put("catalogVersion", rs.getString("catalog_version"));
            version.put("payloadFingerprint", rs.getString("payload_fingerprint"));
            version.put("payload", readJson(rs.getString("payload_json")));
            version.put("operatorUsername", rs.getString("operator_username"));
            version.put("createdTime", instant(rs.getTimestamp("created_time")));
            return version;
        }, tenant(), id);
    }

    public Map<String, Object> validate(Map<String, Object> request) {
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        ListingCatalogService.Catalog catalog = catalogService.active(normalizeType(text(payload.get("productType"))));
        Validation result = validatePayload(payload, catalog);
        Map<String, Object> contentPolicy = contentPolicyService.inspect(payload);
        List<String> errors = new ArrayList<>(result.errors());
        List<Map<String, Object>> fieldErrors = new ArrayList<>(result.fieldErrors());
        if (!Boolean.TRUE.equals(contentPolicy.get("valid"))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> findings = (List<Map<String, Object>>) contentPolicy.get("findings");
            findings.forEach(finding -> {
                errors.add(text(finding.get("message")));
                fieldErrors.add(new LinkedHashMap<>(finding));
            });
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", errors.isEmpty());
        response.put("errors", errors);
        response.put("fieldErrors", fieldErrors);
        response.put("warnings", result.warnings());
        response.put("contentPolicy", contentPolicy);
        response.put("preview", preview(payload));
        response.put("fieldReadiness", fieldReadiness(payload));
        response.put("catalogVersion", catalog.version());
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

    @Transactional
    public Map<String, Object> recordPreflight(Map<String, Object> request,
                                               Map<String, Object> validation,
                                               Map<String, Object> platformResult) {
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        String requestId = text(request.get("requestId"));
        if (requestId.isBlank() || requestId.length() > 64) {
            throw new BusinessException(400, "发布预检必须提供不超过64个字符的requestId");
        }
        String payloadFingerprint = text(platformResult.get("payloadFingerprint"));
        if (payloadFingerprint.isBlank()) payloadFingerprint = fingerprint(request);
        String catalogVersion = text(validation.get("catalogVersion"));
        String capabilityFingerprint = fingerprint(capabilityService.capabilities(accountId));

        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT preview_token,payload_fingerprint,expires_time
                  FROM xianyu_listing_preflight_snapshot
                 WHERE tenant_id=? AND request_id=? FOR UPDATE
                """, tenant(), requestId);
        String token;
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (!payloadFingerprint.equals(text(row.get("payload_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同的预检载荷，请生成新的请求 ID");
            }
            Instant expiry = databaseInstant(row.get("expires_time"));
            if (expiry != null && expiry.isAfter(Instant.now())) {
                token = text(row.get("preview_token"));
                expiresAt = expiry;
            } else {
                token = UUID.randomUUID().toString().replace("-", "");
                jdbcTemplate.update("""
                        UPDATE xianyu_listing_preflight_snapshot
                           SET preview_token=?,catalog_version=?,capability_fingerprint=?,validation_json=?,
                               platform_result_json=?,status='READY',expires_time=?,consumed_time=NULL,
                               operator_user_id=?,operator_username=?
                         WHERE tenant_id=? AND request_id=?
                        """, token, catalogVersion, capabilityFingerprint, json(validation), json(platformResult),
                        Timestamp.from(expiresAt), UserContext.getUserId(), UserContext.getUsername(), tenant(), requestId);
            }
        } else {
            token = UUID.randomUUID().toString().replace("-", "");
            jdbcTemplate.update("""
                    INSERT INTO xianyu_listing_preflight_snapshot
                    (tenant_id,xianyu_account_id,request_id,preview_token,draft_id,draft_revision,catalog_version,
                     payload_fingerprint,capability_fingerprint,validation_json,platform_result_json,status,
                     expires_time,operator_user_id,operator_username)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,'READY',?,?,?)
                    """, tenant(), accountId, requestId, token, nullableLong(request.get("draftId")),
                    nullableInteger(request.get("draftRevision")), catalogVersion, payloadFingerprint,
                    capabilityFingerprint, json(validation), json(platformResult), Timestamp.from(expiresAt),
                    UserContext.getUserId(), UserContext.getUsername());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("previewToken", token);
        evidence.put("expiresAt", expiresAt);
        evidence.put("catalogVersion", catalogVersion);
        evidence.put("payloadFingerprint", payloadFingerprint);
        evidence.put("capabilityFingerprint", capabilityFingerprint);
        return evidence;
    }

    @Transactional
    public Map<String, Object> consumePreflight(Map<String, Object> request) {
        Map<String, Object> payload = payload(request);
        Long accountId = number(payload.get("xianyuAccountId"));
        accountAccessService.requireAccess(accountId);
        String token = text(request.get("previewToken"));
        if (token.isBlank()) throw new BusinessException(409, "缺少发布预检凭证，请重新执行发布前校验");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id,request_id,catalog_version,payload_fingerprint,
                       capability_fingerprint,status,expires_time,consumed_time
                  FROM xianyu_listing_preflight_snapshot
                 WHERE tenant_id=? AND preview_token=? FOR UPDATE
                """, tenant(), token);
        if (rows.isEmpty()) throw new BusinessException(409, "发布预检凭证不存在，请重新预检");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("xianyu_account_id")).longValue() != accountId) {
            throw new BusinessException(409, "发布账号已变化，请重新预检");
        }
        if (!text(row.get("request_id")).equals(text(request.get("requestId")))) {
            throw new BusinessException(409, "发布请求 ID 与预检不一致，请重新预检");
        }
        Instant expiry = databaseInstant(row.get("expires_time"));
        if (expiry == null || !expiry.isAfter(Instant.now())) {
            jdbcTemplate.update("UPDATE xianyu_listing_preflight_snapshot SET status='EXPIRED' WHERE id=?", row.get("id"));
            throw new BusinessException(409, "发布预检已过期，请重新预检");
        }
        String incomingFingerprint = fingerprint(request);
        if (!text(row.get("payload_fingerprint")).equals(incomingFingerprint)) {
            throw new BusinessException(409, "商品内容已变化，原预检失效，请重新预检");
        }
        String currentCatalog = catalogService.active(normalizeType(text(payload.get("productType")))).version();
        if (!currentCatalog.equals(text(row.get("catalog_version")))) {
            throw new BusinessException(409, "类目目录版本已变化，请重新预检");
        }
        String currentCapability = fingerprint(capabilityService.capabilities(accountId));
        if (!currentCapability.equals(text(row.get("capability_fingerprint")))) {
            throw new BusinessException(409, "店铺通道能力已变化，请重新预检");
        }
        jdbcTemplate.update("""
                UPDATE xianyu_listing_preflight_snapshot
                   SET status='CONSUMED',consumed_time=COALESCE(consumed_time,CURRENT_TIMESTAMP(3))
                 WHERE id=?
                """, row.get("id"));
        return row;
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
        capabilityService.requireExecutableChannel(accountId, channelCode);
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

    public Map<String, Object> platformDifferences(Map<String, Object> request, Map<String, Object> result) {
        Map<String, Object> payload = payload(request);
        Map<?, ?> platform = result.get("platform") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> category = platform.get("category") instanceof Map<?, ?> map ? map : Map.of();
        List<Map<String, Object>> differences = new ArrayList<>();
        String requestedCategory = text(payload.get("leafCategoryCode"));
        String returnedCategory = firstText(category, "categoryCode", "catId", "categoryId");
        if (returnedCategory.isBlank()) {
            differences.add(difference("leafCategoryCode", requestedCategory, null, "UNAVAILABLE",
                    "平台预检未返回可比对的类目编码"));
        } else if (!requestedCategory.equals(returnedCategory)) {
            differences.add(difference("leafCategoryCode", requestedCategory, returnedCategory, "DIFFERENT",
                    "平台返回类目与本地参考目录不同，执行前需人工确认"));
        }
        Object finalRequest = platform.get("finalRequest");
        if (!(finalRequest instanceof Map<?, ?>)) {
            differences.add(difference("finalRequest", "结构化草稿", null, "UNAVAILABLE",
                    "当前通道未返回最终请求构造结果"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", differences.stream().anyMatch(item -> "DIFFERENT".equals(item.get("status")))
                ? "DIFFERENT" : differences.isEmpty() ? "SAME" : "PARTIAL");
        response.put("items", differences);
        response.put("requestedCatalogVersion", payload.get("catalogVersion"));
        response.put("dataSource", platform.get("dataSource"));
        return response;
    }

    static Validation validatePayload(Map<String, Object> payload) {
        return validatePayload(payload, null);
    }

    private static Validation validatePayload(Map<String, Object> payload, ListingCatalogService.Catalog catalog) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> fieldErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String type = normalizeType(text(payload.get("productType")));
        String title = text(payload.get("name"));
        String description = text(payload.get("description"));
        if (title.isBlank()) addError(errors, fieldErrors, "name", "REQUIRED", "商品标题不能为空");
        if (title.length() > 120) addError(errors, fieldErrors, "name", "MAX_LENGTH", "商品标题不能超过120个字符");
        if (description.isBlank()) addError(errors, fieldErrors, "description", "REQUIRED", "商品详情不能为空");
        if (description.length() > 3000) addError(errors, fieldErrors, "description", "MAX_LENGTH", "商品详情不能超过3000个字符");

        BigDecimal price = decimal(payload.get("amount"));
        BigDecimal original = decimal(payload.get("originalPrice"));
        if (price == null || price.signum() <= 0 || price.stripTrailingZeros().scale() > 2
                || price.compareTo(new BigDecimal("99999999.99")) > 0) {
            addError(errors, fieldErrors, "amount", "PRICE_RANGE", "售价必须大于0、不超过99999999.99且最多保留两位小数");
        }
        if (original != null && (original.signum() <= 0 || original.stripTrailingZeros().scale() > 2
                || original.compareTo(new BigDecimal("99999999.99")) > 0)) {
            addError(errors, fieldErrors, "originalPrice", "PRICE_RANGE", "原价必须大于0、不超过99999999.99且最多保留两位小数");
        } else if (original != null && price != null && original.compareTo(price) < 0) {
            addError(errors, fieldErrors, "originalPrice", "LOWER_THAN_PRICE", "原价不能低于售价");
        }
        BigDecimal stockValue = decimal(payload.get("stock"));
        if (stockValue == null || stockValue.stripTrailingZeros().scale() > 0
                || stockValue.compareTo(BigDecimal.ONE) < 0
                || stockValue.compareTo(new BigDecimal("999999999")) > 0) {
            addError(errors, fieldErrors, "stock", "INTEGER_RANGE", "库存必须为1至999999999的整数");
        }
        if (text(payload.get("outerId")).length() > 64) {
            addError(errors, fieldErrors, "outerId", "MAX_LENGTH", "商家编码不能超过64个字符");
        }

        List<?> images = payload.get("images") instanceof List<?> list ? list : List.of();
        if (images.isEmpty() || images.size() > 9) {
            addError(errors, fieldErrors, "images", "COUNT_RANGE", "商品图片必须为1至9张");
        }
        Set<String> imageSet = new HashSet<>();
        for (int index = 0; index < images.size(); index++) {
            String image = text(images.get(index));
            if (!(image.startsWith("https://") || image.startsWith("/media/"))) {
                addError(errors, fieldErrors, "images[" + index + "]", "INVALID_URL", "商品图片必须使用 HTTPS 地址或本地媒体地址");
            } else if (!imageSet.add(image)) {
                addError(errors, fieldErrors, "images[" + index + "]", "DUPLICATE", "商品图片不能重复");
            }
        }
        String video = text(payload.get("videoUrl"));
        if (!video.isBlank() && !(video.startsWith("https://") || video.startsWith("/media/"))) {
            addError(errors, fieldErrors, "videoUrl", "INVALID_URL", "商品视频必须使用 HTTPS 地址或本地媒体地址");
        }

        String shipping = text(payload.get("shippingMode"));
        if (shipping.isBlank()) addError(errors, fieldErrors, "shippingMode", "REQUIRED", "请选择交付或运费方式");
        if ("PHYSICAL".equals(type) && Set.of("ONLINE_DELIVERY", "REMOTE_SERVICE", "ON_SITE_SERVICE", "STORE_SERVICE").contains(shipping)) {
            addError(errors, fieldErrors, "shippingMode", "TYPE_MISMATCH", "实物商品不能使用线上或服务交付");
        }
        if ("VIRTUAL".equals(type) && Set.of("FREE_SHIPPING", "FREIGHT_TEMPLATE", "SELF_PICKUP", "ON_SITE_SERVICE", "STORE_SERVICE").contains(shipping)) {
            addError(errors, fieldErrors, "shippingMode", "TYPE_MISMATCH", "虚拟商品不能使用快递、到店或上门方式");
        }
        if ("SERVICE".equals(type) && !Set.of("REMOTE_SERVICE", "ON_SITE_SERVICE", "STORE_SERVICE").contains(shipping)) {
            addError(errors, fieldErrors, "shippingMode", "TYPE_MISMATCH", "服务商品必须选择远程、上门或到店履约");
        }
        if ("FREIGHT_TEMPLATE".equals(shipping) && text(payload.get("freightTemplateId")).isBlank()) {
            addError(errors, fieldErrors, "freightTemplateId", "REQUIRED", "使用运费模板时必须填写模板ID");
        }
        if ("VIRTUAL".equals(type)) {
            if (text(payload.get("fulfillmentMode")).isBlank()) addError(errors, fieldErrors, "fulfillmentMode", "REQUIRED", "请选择虚拟商品履约方式");
            if (intValue(payload.get("validityDays"), 0) < 1) addError(errors, fieldErrors, "validityDays", "INTEGER_RANGE", "虚拟商品有效期必须至少1天");
            if (text(payload.get("supportPolicy")).isBlank()) addError(errors, fieldErrors, "supportPolicy", "REQUIRED", "请填写虚拟商品售后说明");
        } else if ("SERVICE".equals(type)) {
            if (intValue(payload.get("serviceDurationMinutes"), 0) < 1) addError(errors, fieldErrors, "serviceDurationMinutes", "INTEGER_RANGE", "请填写服务时长");
            if (intValue(payload.get("appointmentLeadHours"), -1) < 0) addError(errors, fieldErrors, "appointmentLeadHours", "INTEGER_RANGE", "预约提前时间不能为负数");
            if (text(payload.get("supportPolicy")).isBlank()) addError(errors, fieldErrors, "supportPolicy", "REQUIRED", "请填写服务改期或售后说明");
            if ("ON_SITE_SERVICE".equals(shipping) && text(payload.get("serviceArea")).isBlank()) addError(errors, fieldErrors, "serviceArea", "REQUIRED", "上门服务必须填写服务范围");
        } else if (text(payload.get("afterSalesPolicy")).isBlank()) {
            addError(errors, fieldErrors, "afterSalesPolicy", "REQUIRED", "请填写实物商品售后说明");
        }

        validateCatalog(payload, catalog, errors, fieldErrors, warnings);
        validateSkus(payload, errors, fieldErrors, warnings);
        if (!video.isBlank()) warnings.add("视频已保存到草稿；当前真实发布适配器尚未验证视频提交");
        return new Validation(List.copyOf(errors), List.copyOf(warnings), List.copyOf(fieldErrors));
    }

    private static void validateCatalog(Map<String, Object> payload, ListingCatalogService.Catalog catalog,
                                        List<String> errors, List<Map<String, Object>> fieldErrors,
                                        List<String> warnings) {
        String industry = text(payload.get("industryCode"));
        String leafCode = text(payload.get("leafCategoryCode"));
        if (industry.isBlank()) addError(errors, fieldErrors, "industryCode", "REQUIRED", "请选择商品行业");
        if (leafCode.isBlank()) {
            addError(errors, fieldErrors, "leafCategoryCode", "REQUIRED", "请选择叶子类目");
            return;
        }
        if (catalog == null) {
            warnings.add("叶子类目将在目录版本校验时再次核对");
            return;
        }
        Map<String, Object> leaf = catalog.leaf(leafCode);
        if (leaf == null) {
            addError(errors, fieldErrors, "leafCategoryCode", "CATALOG_MISMATCH", "叶子类目不属于当前目录版本");
            return;
        }
        boolean belongs = catalog.industries().stream().anyMatch(item -> industry.equals(text(item.get("code")))
                && item.get("leafCategories") instanceof List<?> list
                && list.stream().anyMatch(candidate -> candidate instanceof Map<?, ?> map && leafCode.equals(text(map.get("code")))));
        if (!belongs) addError(errors, fieldErrors, "leafCategoryCode", "INDUSTRY_MISMATCH", "叶子类目不属于所选行业");
        Map<?, ?> values = payload.get("categoryAttributes") instanceof Map<?, ?> map ? map : Map.of();
        if (leaf.get("attributes") instanceof List<?> attributes) {
            for (Object value : attributes) {
                if (!(value instanceof Map<?, ?> attribute)) continue;
                String code = text(attribute.get("code"));
                String field = "categoryAttributes." + code;
                String selected = text(values.get(code));
                if (Boolean.TRUE.equals(attribute.get("required")) && selected.isBlank()) {
                    addError(errors, fieldErrors, field, "REQUIRED", text(attribute.get("name")) + "为当前类目必填属性");
                }
                if (!selected.isBlank() && attribute.get("options") instanceof List<?> options && !options.isEmpty()
                        && options.stream().noneMatch(option -> selected.equals(text(option)))) {
                    addError(errors, fieldErrors, field, "INVALID_OPTION", text(attribute.get("name")) + "不在目录允许值中");
                }
            }
        }
    }

    private static void validateSkus(Map<String, Object> payload, List<String> errors,
                                     List<Map<String, Object>> fieldErrors, List<String> warnings) {
        List<?> dimensions = payload.get("skuDimensions") instanceof List<?> list ? list : List.of();
        List<?> skus = payload.get("skus") instanceof List<?> list ? list : List.of();
        if (dimensions.size() > 2) addError(errors, fieldErrors, "skuDimensions", "MAX_COUNT", "SKU最多支持2个规格维度");
        if (skus.size() > 50) addError(errors, fieldErrors, "skus", "MAX_COUNT", "SKU组合不能超过50个");
        Set<String> dimensionNames = new HashSet<>();
        long expected = dimensions.isEmpty() ? 0 : 1;
        for (int index = 0; index < dimensions.size(); index++) {
            if (!(dimensions.get(index) instanceof Map<?, ?> dimension)) {
                addError(errors, fieldErrors, "skuDimensions[" + index + "]", "INVALID", "规格维度格式无效");
                continue;
            }
            String name = text(dimension.get("name"));
            if (name.isBlank()) addError(errors, fieldErrors, "skuDimensions[" + index + "].name", "REQUIRED", "规格名称不能为空");
            else if (!dimensionNames.add(name)) addError(errors, fieldErrors, "skuDimensions[" + index + "].name", "DUPLICATE", "规格名称不能重复");
            List<?> values = dimension.get("values") instanceof List<?> list ? list : List.of();
            if (values.isEmpty()) addError(errors, fieldErrors, "skuDimensions[" + index + "].values", "REQUIRED", "每个规格至少需要一个规格值");
            Set<String> distinctValues = new HashSet<>();
            for (Object value : values) {
                String normalized = text(value);
                if (normalized.isBlank() || !distinctValues.add(normalized)) {
                    addError(errors, fieldErrors, "skuDimensions[" + index + "].values", "DUPLICATE", "规格值不能为空且不能重复");
                    break;
                }
            }
            expected *= values.size();
        }
        if (!dimensions.isEmpty() && expected != skus.size()) addError(errors, fieldErrors, "skus", "INCOMPLETE_MATRIX", "SKU组合未完整生成，应为" + expected + "项");
        if (dimensions.isEmpty() && !skus.isEmpty()) addError(errors, fieldErrors, "skus", "MISSING_DIMENSION", "存在SKU时必须先配置规格维度");
        Set<String> keys = new HashSet<>();
        Set<String> merchantCodes = new HashSet<>();
        for (int index = 0; index < skus.size(); index++) {
            if (!(skus.get(index) instanceof Map<?, ?> sku)) {
                addError(errors, fieldErrors, "skus[" + index + "]", "INVALID", "SKU格式无效");
                continue;
            }
            String key = text(sku.get("key"));
            if (key.isBlank() || !keys.add(key)) addError(errors, fieldErrors, "skus[" + index + "].key", "DUPLICATE", "SKU组合名称不能为空且不能重复");
            BigDecimal skuPrice = decimal(sku.get("price"));
            if (skuPrice == null || skuPrice.signum() <= 0 || skuPrice.stripTrailingZeros().scale() > 2
                    || skuPrice.compareTo(new BigDecimal("99999999.99")) > 0) {
                addError(errors, fieldErrors, "skus[" + index + "].price", "PRICE_RANGE", "SKU售价必须大于0且最多两位小数");
            }
            BigDecimal skuOriginal = decimal(sku.get("originalPrice"));
            if (skuOriginal != null && (skuOriginal.stripTrailingZeros().scale() > 2 || skuOriginal.signum() <= 0
                    || (skuPrice != null && skuOriginal.compareTo(skuPrice) < 0))) {
                addError(errors, fieldErrors, "skus[" + index + "].originalPrice", "PRICE_RANGE", "SKU原价必须不低于售价且最多两位小数");
            }
            BigDecimal skuStock = decimal(sku.get("stock"));
            if (skuStock == null || skuStock.stripTrailingZeros().scale() > 0 || skuStock.compareTo(BigDecimal.ZERO) < 0
                    || skuStock.compareTo(new BigDecimal("999999999")) > 0) {
                addError(errors, fieldErrors, "skus[" + index + "].stock", "INTEGER_RANGE", "SKU库存必须为0至999999999的整数");
            }
            String merchantCode = text(sku.get("merchantCode"));
            if (!merchantCode.isBlank() && !merchantCodes.add(merchantCode)) {
                addError(errors, fieldErrors, "skus[" + index + "].merchantCode", "DUPLICATE", "SKU商家编码不能重复");
            }
            String image = text(sku.get("image"));
            if (!image.isBlank() && !(image.startsWith("https://") || image.startsWith("/media/"))) {
                addError(errors, fieldErrors, "skus[" + index + "].image", "INVALID_URL", "SKU图片必须使用 HTTPS 地址或本地媒体地址");
            }
        }
        if (!skus.isEmpty()) warnings.add("多SKU已保存到草稿；仅通道能力验证通过后才会提交平台");
    }

    private static void addError(List<String> errors, List<Map<String, Object>> fieldErrors,
                                 String field, String code, String message) {
        errors.add(message);
        fieldErrors.add(Map.of("field", field, "code", code, "message", message));
    }

    private Map<String, Object> preview(Map<String, Object> payload) {
        Map<String, Object> preview = new LinkedHashMap<>();
        for (String key : List.of("name", "description", "amount", "originalPrice", "stock", "productType",
                "conditionCode", "businessMode", "industryCode", "leafCategoryCode", "leafCategoryName",
                "shippingMode", "shippingFee", "province", "city", "district", "images", "skus",
                "serviceProtocols", "outerId", "fulfillmentMode", "validityDays", "supportPolicy",
                "afterSalesPolicy", "serviceDurationMinutes", "appointmentLeadHours", "serviceArea")) {
            preview.put(key, payload.get(key));
        }
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
        result.put("catalogVersion", rs.getString("catalog_version"));
        result.put("payloadFingerprint", rs.getString("payload_fingerprint"));
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

    private static String normalizeType(String value) { return ListingCatalogService.normalizeType(value); }

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
    private static Long nullableLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (Exception e) { throw new BusinessException(400, "草稿ID无效"); }
    }
    private static Integer nullableInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(value)).intValueExact(); }
        catch (Exception e) { throw new BusinessException(400, "草稿版本无效"); }
    }
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

    private static String firstText(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            String value = text(values.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Map<String, Object> difference(String field, Object requested, Object returned,
                                                   String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("requested", requested);
        item.put("returned", returned);
        item.put("status", status);
        item.put("message", message);
        return item;
    }

    private static Map<String, Object> typeRequirements(String type) {
        if ("PHYSICAL".equals(type)) return Map.of(
                "fulfillmentFields", List.of("shippingMode", "freightTemplateId", "province", "city"),
                "afterSalesFields", List.of("afterSalesPolicy"),
                "notice", "实物商品必须说明成色、物流与售后，不可切换为线上交付");
        if ("SERVICE".equals(type)) return Map.of(
                "fulfillmentFields", List.of("shippingMode", "serviceDurationMinutes", "appointmentLeadHours", "serviceArea"),
                "afterSalesFields", List.of("supportPolicy"),
                "notice", "服务商品必须说明时长、预约规则和服务范围，不生成快递物流");
        return Map.of(
                "fulfillmentFields", List.of("fulfillmentMode", "validityDays"),
                "afterSalesFields", List.of("supportPolicy"),
                "notice", "虚拟商品必须说明交付方式、有效期和售后，不生成快递物流");
    }

    private String requireCurrentCatalog(Map<String, Object> payload) {
        ListingCatalogService.Catalog current = catalogService.active(normalizeType(text(payload.get("productType"))));
        String requested = text(payload.get("catalogVersion"));
        if (!requested.isBlank() && !requested.equals(current.version())) {
            throw new BusinessException(409, "商品类目目录已更新，请刷新类目后再保存");
        }
        return current.version();
    }

    private String fingerprint(Object value) {
        Map<String, Object> map = value instanceof Map<?, ?> raw ? normalizeMap(raw) : Map.of("value", value);
        return MerchantOperationsService.publishPayloadFingerprint(objectMapper, map);
    }

    static Instant databaseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof java.util.Date date) return date.toInstant();
        throw new IllegalArgumentException("不支持的数据库时间类型：" + value.getClass().getName());
    }

    private Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private void insertVersion(Long draftId, int revision, String source, String catalogVersion,
                               String fingerprint, Map<String, Object> payload) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_listing_draft_version
                (tenant_id,draft_id,revision,change_source,catalog_version,payload_fingerprint,payload_json,
                 operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, tenant(), draftId, revision, source, catalogVersion, fingerprint, json(payload),
                UserContext.getUserId(), UserContext.getUsername());
    }

    private String changeSource(Map<String, Object> request) {
        String source = text(request.get("changeSource")).toUpperCase(Locale.ROOT);
        return Set.of("AUTO_SAVE", "MANUAL_SAVE", "RESTORE").contains(source) ? source : "MANUAL_SAVE";
    }

    private String requestId(Map<String, Object> request) {
        String requestId = text(request.get("requestId"));
        if (requestId.length() > 64) throw new BusinessException(400, "requestId不能超过64个字符");
        return requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private void auditDraft(Long accountId, Long draftId, String operation, String requestId,
                            Object before, Object after, Object diff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType("UPDATE");
        log.setOperationModule("PRODUCT_PUBLISHING");
        log.setOperationDesc(operation);
        log.setOperationStatus(1);
        log.setTargetType("LISTING_DRAFT");
        log.setTargetId(String.valueOf(draftId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL_DRAFT");
        log.setRequestParams(json(Map.of("operation", operation)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("before", before);
        response.put("after", after);
        log.setResponseResult(json(response));
        log.setFieldDiffJson(json(diff));
        operationLogService.logRequired(log);
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

    record Validation(List<String> errors, List<String> warnings, List<Map<String, Object>> fieldErrors) { }
}
