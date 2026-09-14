package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 隔离 QA 的商品批任务平台替身。必须同时满足 qa profile、显式开关、租户、店铺和商品前缀；
 * 默认关闭，任何不在白名单内的商品都会继续走真实能力预检且绝不会进入本替身。
 */
@Service
public class ProductBatchQaMockService {

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long tenantId;
    private final Set<Long> accountIds;
    private final String goodsPrefix;

    public ProductBatchQaMockService(Environment environment,
                                     ObjectMapper objectMapper,
                                     @Value("${app.product-batch.qa-mock.enabled:false}") boolean enabled,
                                     @Value("${app.product-batch.qa-mock.tenant-id:-1}") long tenantId,
                                     @Value("${app.product-batch.qa-mock.account-ids:}") String accountIds,
                                     @Value("${app.product-batch.qa-mock.goods-prefix:QA-}") String goodsPrefix) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.tenantId = tenantId;
        this.accountIds = Arrays.stream(accountIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
        this.goodsPrefix = goodsPrefix == null ? "" : goodsPrefix.trim();
    }

    @PostConstruct
    void validateSafetyBoundary() {
        if (!enabled) return;
        boolean qaProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch("qa"::equalsIgnoreCase);
        if (!qaProfile || tenantId <= 0 || accountIds.isEmpty() || goodsPrefix.length() < 3) {
            throw new IllegalStateException("商品批任务 QA Mock 仅允许在 qa profile 且租户/店铺/商品前缀白名单完整时启用");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isEligible(Long currentTenantId, Long accountId, String goodsId) {
        return enabled && currentTenantId != null && currentTenantId == tenantId
                && accountId != null && accountIds.contains(accountId)
                && goodsId != null && goodsId.startsWith(goodsPrefix);
    }

    public Map<String, Object> publicConfiguration() {
        return Map.of("enabled", enabled, "profileRequired", "qa", "tenantId", tenantId,
                "accountIds", accountIds, "goodsPrefix", goodsPrefix,
                "platformNetworkCalls", false,
                "scenarios", Set.of("SUCCESS", "FAIL_ONCE", "FAIL_ON_ACCOUNT_102", "MIXED_100", "UNKNOWN", "AUTH_REVOKED", "STALE_VERSION", "THROTTLE"));
    }

    public String scenario(Map<String, Object> job) {
        Object value = parameters(job).get("qaScenario");
        return value == null ? "SUCCESS" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    public boolean bypassRateLimit(Map<String, Object> job) {
        Object value = parameters(job).get("qaBypassRateLimit");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    public boolean simulateAuthorizationRevoked(Map<String, Object> job) {
        return "AUTH_REVOKED".equals(scenario(job));
    }

    public boolean simulateStaleVersion(Map<String, Object> job) {
        return "STALE_VERSION".equals(scenario(job));
    }

    public Map<String, Object> execute(Map<String, Object> job, Map<String, Object> item) {
        String scenario = scenario(job);
        long accountId = number(item.get("xianyu_account_id"));
        String goodsId = String.valueOf(item.get("xy_goods_id"));
        int attemptsBeforeClaim = integer(item.get("attempt_count"));
        int bucket = numericSuffix(goodsId) % 100;

        if ("UNKNOWN".equals(scenario) || "MIXED_100".equals(scenario) && bucket >= 95) {
            throw new IllegalStateException("QA_MOCK 平台请求超时，结果无法确认");
        }
        boolean failOnce = "FAIL_ONCE".equals(scenario)
                || "FAIL_ON_ACCOUNT_102".equals(scenario) && accountId == 102L
                || "MIXED_100".equals(scenario) && bucket >= 80 && bucket < 95;
        if (failOnce && attemptsBeforeClaim == 0) {
            throw new ManualRetryRequiredException("QA_MOCK 可重试失败；等待人工选择失败项重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("executionChannel", "QA_MOCK");
        result.put("scenario", scenario);
        result.put("accountId", accountId);
        result.put("goodsId", goodsId);
        result.put("operationType", item.get("operation_type"));
        result.put("attempt", attemptsBeforeClaim + 1);
        result.put("platformNetworkCalled", false);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameters(Map<String, Object> job) {
        Object raw = job.get("operation_params_json");
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (raw == null || String.valueOf(raw).isBlank()) return Map.of();
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private int numericSuffix(String value) {
        int start = value.length();
        while (start > 0 && Character.isDigit(value.charAt(start - 1))) start--;
        if (start == value.length()) return Math.floorMod(value.hashCode(), 100);
        try { return Math.floorMod(Integer.parseInt(value.substring(start)), 100); }
        catch (NumberFormatException ignored) { return Math.floorMod(value.hashCode(), 100); }
    }

    public static final class ManualRetryRequiredException extends RuntimeException {
        public ManualRetryRequiredException(String message) { super(message); }
    }
}
