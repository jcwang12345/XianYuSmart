package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品发布的隔离 QA 平台替身。默认关闭，并由 profile、租户、账号、通道和标题前缀五重约束。
 * 任何未命中白名单的请求都不会进入替身，也不会把 QA 结果伪装成真实平台结果。
 */
@Service
public class PublishQaMockService {

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final GoodsInfoService goodsInfoService;
    private final boolean enabled;
    private final long tenantId;
    private final Set<Long> accountIds;
    private final String titlePrefix;

    public PublishQaMockService(Environment environment,
                                ObjectMapper objectMapper,
                                GoodsInfoService goodsInfoService,
                                @Value("${app.publishing.qa-mock.enabled:false}") boolean enabled,
                                @Value("${app.publishing.qa-mock.tenant-id:-1}") long tenantId,
                                @Value("${app.publishing.qa-mock.account-ids:}") String accountIds,
                                @Value("${app.publishing.qa-mock.title-prefix:QA-PUBLISH-}") String titlePrefix) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.goodsInfoService = goodsInfoService;
        this.enabled = enabled;
        this.tenantId = tenantId;
        this.accountIds = Arrays.stream(accountIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        this.titlePrefix = titlePrefix == null ? "" : titlePrefix.trim();
    }

    @PostConstruct
    void validateSafetyBoundary() {
        if (!enabled) return;
        boolean qaProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch("qa"::equalsIgnoreCase);
        if (!qaProfile || tenantId <= 0 || accountIds.isEmpty() || titlePrefix.length() < 6) {
            throw new IllegalStateException("商品发布 QA Mock 仅允许在 qa profile 且租户/账号/标题前缀白名单完整时启用");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isEligible(Long currentTenantId, Long accountId, String channel, String title) {
        return enabled && currentTenantId != null && currentTenantId == tenantId
                && accountId != null && accountIds.contains(accountId)
                && "QA_LOCAL".equals(channel)
                && title != null && title.startsWith(titlePrefix);
    }

    public void requireEligible(Long currentTenantId, Long accountId, String channel, String title) {
        if (!isEligible(currentTenantId, accountId, channel, title)) {
            throw new BusinessException(403,
                    "QA_LOCAL 仅允许隔离 QA 租户、白名单账号及 " + titlePrefix + " 前缀商品");
        }
    }

    public Map<String, Object> preflight(Map<String, Object> request, Long currentTenantId, Long accountId) {
        String title = text(request.get("name"));
        requireEligible(currentTenantId, accountId, text(request.get("publishChannel")), title);
        Map<String, Object> finalRequest = new LinkedHashMap<>();
        finalRequest.put("qaFixture", true);
        finalRequest.put("platformNetworkCalls", false);
        finalRequest.put("title", title);
        finalRequest.put("description", text(request.get("description")));
        finalRequest.put("amount", request.get("amount"));
        finalRequest.put("stock", request.getOrDefault("stock", 1));
        finalRequest.put("images", request.getOrDefault("images", List.of()));
        finalRequest.put("category", Map.of(
                "categoryId", "QA-CATEGORY-SOFTWARE",
                "categoryName", "隔离 QA / 软件服务",
                "source", "QA_FIXTURE",
                "coverageStatus", "FULL"));
        finalRequest.put("address", Map.of(
                "addressId", "QA-ADDRESS-NONE",
                "source", "QA_FIXTURE",
                "coverageStatus", "NOT_REQUIRED"));
        return Map.of(
                "valid", true,
                "executionChannel", "QA_MOCK",
                "platformNetworkCalls", false,
                "previewUsesProductionBuilder", false,
                "safeBoundary", publicConfiguration(),
                "category", finalRequest.get("category"),
                "imageCount", request.get("images") instanceof List<?> images ? images.size() : 0,
                "finalRequest", finalRequest);
    }

    public Map<String, Object> execute(MerchantTask task, MerchantResource material, Long currentTenantId,
                                       Long accountId) {
        Map<String, Object> data = read(material.getDataJson());
        String title = text(data.get("name"));
        if (title.isBlank()) title = material.getName();
        requireEligible(currentTenantId, accountId, text(data.get("publishChannel")), title);
        String scenario = text(data.get("qaScenario")).toUpperCase(Locale.ROOT);
        if (scenario.isBlank()) scenario = "SUCCESS";
        if ("UNKNOWN".equals(scenario)) {
            throw new QaPublishOutcomeUnknownException("QA_MOCK 发布结果未知；未调用闲鱼平台");
        }
        if (!Set.of("SUCCESS", "LOCAL_PENDING").contains(scenario)) {
            throw new BusinessException(400, "不支持的 QA 发布场景：" + scenario);
        }
        String itemId = "QA-PUBLISHED-" + task.getId();
        boolean localSynced = !"LOCAL_PENDING".equals(scenario);
        if (localSynced) {
            List<String> images = stringList(data.get("images"));
            String infoPic = json(images.stream().map(image -> Map.of("url", image)).toList());
            boolean saved = goodsInfoService.savePublishedGoods(itemId, accountId, title,
                    images.isEmpty() ? null : images.getFirst(), infoPic, text(data.get("description")),
                    "/qa/products/" + itemId, String.valueOf(material.getAmount()));
            if (!saved) throw new IllegalStateException("QA_MOCK 本地商品记录保存失败");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("itemId", itemId);
        result.put("url", "/qa/products/" + itemId);
        result.put("executionChannel", "QA_MOCK");
        result.put("scenario", scenario);
        result.put("platformNetworkCalls", false);
        result.put("platformWrite", "NOT_PERFORMED");
        result.put("localSynced", localSynced);
        result.put("outcomeState", localSynced ? "QA_CONFIRMED" : "QA_CONFIRMED_LOCAL_PENDING");
        return result;
    }

    public Map<String, Object> publicConfiguration() {
        return Map.of(
                "enabled", enabled,
                "profileRequired", "qa",
                "tenantId", tenantId,
                "accountIds", accountIds,
                "titlePrefix", titlePrefix,
                "publishChannel", "QA_LOCAL",
                "platformNetworkCalls", false,
                "scenarios", Set.of("SUCCESS", "LOCAL_PENDING", "UNKNOWN"));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalStateException("QA 发布素材无法读取", e); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("QA 发布结果无法序列化", e); }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static final class QaPublishOutcomeUnknownException extends RuntimeException {
        public QaPublishOutcomeUnknownException(String message) { super(message); }
    }
}
