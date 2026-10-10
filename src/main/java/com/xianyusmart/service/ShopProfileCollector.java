package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** 账号隔离、只读的真实店铺画像采集器。 */
@Slf4j
@Service
public class ShopProfileCollector {

    static final String HEAD_API = "mtop.idle.web.user.page.head";
    static final String NAV_API = "mtop.idle.web.user.page.nav";
    static final String ITEM_API = "mtop.idle.web.xyh.item.list";
    private static final String PERSONAL_URL = "https://www.goofish.com/personal?userId=";

    private final XianyuApiCallUtils apiCallUtils;
    private final PlaywrightManager playwrightManager;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentMap<Long, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    @Value("${xianyu.shop-profile.browser-fallback-enabled:true}")
    private boolean browserFallbackEnabled = true;

    @Value("${xianyu.shop-profile.browser-fallback-timeout-ms:45000}")
    private int browserFallbackTimeoutMs = 45000;

    @Value("${xianyu.shop-profile.browser-fallback-daily-limit:1}")
    private int browserFallbackDailyLimit = 1;

    public ShopProfileCollector(XianyuApiCallUtils apiCallUtils,
                                PlaywrightManager playwrightManager,
                                ObjectMapper objectMapper) {
        this(apiCallUtils, playwrightManager, objectMapper, null);
    }

    @Autowired
    public ShopProfileCollector(XianyuApiCallUtils apiCallUtils,
                                PlaywrightManager playwrightManager,
                                ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate) {
        this.apiCallUtils = apiCallUtils;
        this.playwrightManager = playwrightManager;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CollectionResult collect(Long accountId, String userId, String cookieText) {
        if (accountId == null || userId == null || userId.isBlank() || cookieText == null || cookieText.isBlank()) {
            return CollectionResult.failed("CREDENTIAL_UNAVAILABLE", "账号身份或登录凭证不可用", false);
        }
        ReentrantLock lock = accountLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) return CollectionResult.busyResult();
        try {
            Map<String, Object> identityData = Map.of("userId", userId, "self", false);
            XianyuApiCallUtils.ApiCallResult head = apiCallUtils.callApiWithRetry(
                    accountId, HEAD_API, identityData, cookieText);
            XianyuApiCallUtils.ApiCallResult nav = apiCallUtils.callApiWithRetry(
                    accountId, NAV_API, Map.of("userId", userId), cookieText);
            XianyuApiCallUtils.ApiCallResult items = apiCallUtils.callApiWithRetry(
                    accountId, ITEM_API, Map.of("userId", userId, "needGroupInfo", true,
                            "pageNumber", 1, "pageSize", 20), cookieText);
            if (completeResponse(head, HEAD_API, userId)
                    && completeResponse(nav, NAV_API, userId)
                    && completeResponse(items, ITEM_API, userId)) {
                return CollectionResult.success("PLATFORM_API", head.getResponse(), nav.getResponse(), items.getResponse(), false);
            }
            if (!browserFallbackEnabled) {
                return failure(head, nav, items, "只读页面兜底已关闭", "PLATFORM_API");
            }
            if (!fallbackAvailable(accountId)) {
                return CollectionResult.failed("RATE_LIMITED", "该账号今日页面补采次数已达上限", false);
            }
            CollectionResult fallback = collectFromPage(accountId, userId, cookieText);
            if (fallback.success()) {
                return CollectionResult.merged(
                        successful(head) ? head.getResponse() : null,
                        successful(nav) ? nav.getResponse() : null,
                        successful(items) ? items.getResponse() : null,
                        fallback.headJson(), fallback.navJson(), fallback.itemJson());
            }
            return failure(head, nav, items, fallback.errorMessage(), "PLATFORM_WEB");
        } catch (Exception e) {
            log.warn("店铺画像只读采集异常: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
            return CollectionResult.failed("COLLECTOR_ERROR", "店铺画像采集暂不可用", false);
        } finally {
            lock.unlock();
        }
    }

    private CollectionResult collectFromPage(Long accountId, String userId, String cookieText) {
        Map<String, String> responses = Collections.synchronizedMap(new LinkedHashMap<>());
        int timeout = Math.max(5_000, Math.min(120_000, browserFallbackTimeoutMs));
        try (BrowserContext context = playwrightManager.createContext(accountId)) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.setDefaultTimeout(Math.min(timeout, 15_000));
            page.onResponse(response -> capture(response, userId, responses));
            page.navigate(PERSONAL_URL + userId,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeout));
            long deadline = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < deadline && responses.size() < 3) page.waitForTimeout(150);
            if (requiresVerification(page)) {
                return CollectionResult.failed("VERIFICATION_REQUIRED", "平台要求重新扫码或完成账号验证", true);
            }
            if (responses.keySet().containsAll(List.of(HEAD_API, NAV_API, ITEM_API))) {
                return CollectionResult.success("PLATFORM_WEB", responses.get(HEAD_API), responses.get(NAV_API),
                        responses.get(ITEM_API), true);
            }
            return CollectionResult.failed("PAGE_EVIDENCE_INCOMPLETE", "个人页未捕获到完整画像接口证据", false);
        } catch (Exception e) {
            log.warn("店铺画像页面兜底失败: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
            return CollectionResult.failed("PAGE_FALLBACK_FAILED", "个人页只读补采失败", false);
        }
    }

    void capture(Response response, String expectedUserId, Map<String, String> responses) {
        try {
            String api = apiName(response.url());
            if (api == null || responses.containsKey(api) || !response.ok()) return;
            if (!requestTargets(response.url(), expectedUserId)) return;
            String text = response.text();
            if (text == null || text.length() > 2_000_000) return;
            JsonNode data = successData(text);
            if (!embeddedIdentityMatches(data, expectedUserId)) return;
            responses.put(api, text);
        } catch (Exception ignored) {
            // 不是目标响应，或正文不可读；不记录可能含敏感参数的 URL。
        }
    }

    private String apiName(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase();
        if (!lower.startsWith("https://h5api.m.goofish.com/")) return null;
        for (String api : List.of(HEAD_API, NAV_API, ITEM_API)) if (lower.contains("/" + api + "/")) return api;
        return null;
    }

    private boolean requestTargets(String url, String expectedUserId) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return false;
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && "data".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                    JsonNode data = objectMapper.readTree(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    return expectedUserId.equals(data.path("userId").asText());
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean requiresVerification(Page page) {
        try {
            String url = page.url() == null ? "" : page.url().toLowerCase();
            String body = page.locator("body").innerText();
            return url.contains("login") || body.contains("扫码登录") || body.contains("请先登录")
                    || body.contains("完成验证") || body.contains("安全验证");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void addCookies(BrowserContext context, String cookieText) {
        List<Cookie> cookies = new ArrayList<>();
        for (String part : cookieText.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) {
                cookies.add(new Cookie(pair[0].trim(), pair[1].trim())
                        .setDomain(".goofish.com").setPath("/").setSecure(true));
            }
        }
        if (!cookies.isEmpty()) context.addCookies(cookies);
    }

    private boolean successful(XianyuApiCallUtils.ApiCallResult result) {
        return result != null && result.isSuccess() && result.getResponse() != null;
    }

    private boolean completeResponse(XianyuApiCallUtils.ApiCallResult result, String api, String expectedUserId) {
        if (!successful(result)) return false;
        try {
            JsonNode data = successData(result.getResponse());
            if (!embeddedIdentityMatches(data, expectedUserId)) return false;
            if (HEAD_API.equals(api)) {
                return present(data, "/baseInfo/kcUserId")
                        && present(data, "/baseInfo/tags")
                        && present(data, "/baseInfo/userType")
                        && present(data, "/module/base/displayName")
                        && present(data, "/module/base/avatar/avatar")
                        && present(data, "/module/base/ipLocation")
                        && present(data, "/module/base/ylzTags")
                        && present(data, "/module/shop/level")
                        && present(data, "/module/shop/score")
                        && present(data, "/module/shop/superShow")
                        && present(data, "/module/shop/itemToppingLimit")
                        && present(data, "/module/shop/reviewNum")
                        && present(data, "/module/shop/praiseRatio")
                        && present(data, "/module/social/followers")
                        && present(data, "/module/tabs/item/number");
            }
            if (NAV_API.equals(api)) {
                return present(data, "/module/base/purchaseCount") && present(data, "/module/base/soldCount");
            }
            return data.has("itemGroupList");
        } catch (Exception ignored) {
            return false;
        }
    }

    private JsonNode successData(String json) throws Exception {
        String value = json.trim();
        if (!value.startsWith("{")) {
            int first = value.indexOf('('), last = value.lastIndexOf(')');
            if (first < 0 || last <= first) throw new IllegalArgumentException();
            value = value.substring(first + 1, last);
        }
        JsonNode root = objectMapper.readTree(value);
        if (!root.path("ret").isArray() || root.path("ret").isEmpty()
                || !root.path("ret").get(0).asText("").startsWith("SUCCESS")) throw new IllegalArgumentException();
        JsonNode data = root.path("data");
        if (!data.isObject()) throw new IllegalArgumentException();
        return data;
    }

    private boolean embeddedIdentityMatches(JsonNode data, String expectedUserId) {
        for (String pointer : List.of("/baseInfo/kcUserId", "/baseInfo/userId", "/module/base/kcUserId",
                "/module/base/userId", "/userId")) {
            JsonNode node = data.at(pointer);
            if (!node.isMissingNode() && !node.isNull() && !expectedUserId.equals(node.asText())) return false;
        }
        return true;
    }

    private boolean present(JsonNode data, String pointer) {
        JsonNode node = data.at(pointer);
        return !node.isMissingNode() && !node.isNull();
    }

    private CollectionResult failure(XianyuApiCallUtils.ApiCallResult head,
                                     XianyuApiCallUtils.ApiCallResult nav,
                                     XianyuApiCallUtils.ApiCallResult items,
                                     String fallback, String source) {
        boolean expired = java.util.stream.Stream.of(head, nav, items).filter(java.util.Objects::nonNull)
                .anyMatch(XianyuApiCallUtils.ApiCallResult::isTokenExpired);
        boolean limited = java.util.stream.Stream.of(head, nav, items).filter(java.util.Objects::nonNull)
                .anyMatch(XianyuApiCallUtils.ApiCallResult::isGuardBlocked);
        boolean verification = java.util.stream.Stream.of(head, nav, items).filter(java.util.Objects::nonNull)
                .anyMatch(result -> safe(result.getErrorMessage()).contains("验证") || safe(result.getErrorMessage()).contains("登录"));
        String code = expired ? "CREDENTIAL_EXPIRED" : limited ? "RATE_LIMITED"
                : verification ? "VERIFICATION_REQUIRED" : "UNAVAILABLE";
        String message = expired ? "账号登录凭据已过期" : limited ? "平台请求处于限速退避期"
                : verification ? "平台要求重新扫码或完成账号验证" : fallback;
        return CollectionResult.failed(source, code, message, verification || expired);
    }

    private boolean fallbackAvailable(Long accountId) {
        if (jdbcTemplate == null || browserFallbackDailyLimit < 1) return jdbcTemplate == null;
        Long tenantId = com.xianyusmart.context.TenantContext.get();
        if (tenantId == null) return true;
        Integer used = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_shop_profile_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND source IN ('PLATFORM_WEB','MIXED')
                   AND started_at>=CURRENT_DATE() AND started_at<DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY)
                """, Integer.class, tenantId, accountId);
        return used == null || used < browserFallbackDailyLimit;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record CollectionResult(boolean success, boolean busy, String source,
                                   String headJson, String navJson, String itemJson,
                                   String pageHeadJson, String pageNavJson, String pageItemJson,
                                   String errorCode, String errorMessage,
                                   boolean verificationRequired, boolean browserFallbackUsed) {
        static CollectionResult success(String source, String head, String nav, String item, boolean fallback) {
            return new CollectionResult(true, false, source, head, nav, item, null, null, null,
                    null, null, false, fallback);
        }

        static CollectionResult merged(String head, String nav, String item,
                                       String pageHead, String pageNav, String pageItem) {
            return new CollectionResult(true, false, "MIXED", head, nav, item,
                    pageHead, pageNav, pageItem, null, null, false, true);
        }

        static CollectionResult failed(String code, String message, boolean verificationRequired) {
            return failed(null, code, message, verificationRequired);
        }

        static CollectionResult failed(String source, String code, String message, boolean verificationRequired) {
            return new CollectionResult(false, false, source, null, null, null, null, null, null, code,
                    message == null || message.isBlank() ? "店铺画像采集失败" : message,
                    verificationRequired, false);
        }

        static CollectionResult busyResult() {
            return new CollectionResult(false, true, null, null, null, null, null, null, null, "BUSY",
                    "该账号已有画像采集任务", false, false);
        }
    }
}
