package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 商品详情的统一只读获取链路。优先使用带令牌刷新的 MTop API，失败后才使用
 * 账号隔离的 Playwright 上下文打开商品页并捕获同一个详情接口的响应。
 *
 * <p>浏览器兜底只读取页面和网络响应，不点击、提交或修改任何平台数据。</p>
 */
@Slf4j
@Service
public class PlatformItemDetailFetchService {

    static final String DETAIL_API = "mtop.taobao.idle.pc.detail";
    private static final String ITEM_URL = "https://www.goofish.com/item?id=";
    private static final int MIN_FALLBACK_TIMEOUT_MS = 5_000;
    private static final int MAX_FALLBACK_TIMEOUT_MS = 120_000;

    private final PlaywrightManager playwrightManager;
    private final XianyuApiCallUtils apiCallUtils;
    private final ObjectMapper objectMapper;
    // 锁按已授权账号长期复用。不要在 unlock 后移除，否则新请求可能创建第二把锁并并行进入。
    private final ConcurrentMap<Long, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    @Value("${xianyu.item-detail.browser-fallback-enabled:true}")
    private boolean browserFallbackEnabled = true;

    @Value("${xianyu.item-detail.browser-fallback-timeout-ms:45000}")
    private int browserFallbackTimeoutMs = 45000;

    public PlatformItemDetailFetchService(PlaywrightManager playwrightManager,
                                          XianyuApiCallUtils apiCallUtils,
                                          ObjectMapper objectMapper) {
        this.playwrightManager = playwrightManager;
        this.apiCallUtils = apiCallUtils;
        this.objectMapper = objectMapper;
    }

    public FetchResult fetch(Long accountId, String itemId, String cookieText) {
        if (accountId == null || itemId == null || itemId.isBlank()) {
            return FetchResult.unavailable("账号或商品 ID 为空");
        }
        if (cookieText == null || cookieText.isBlank()) {
            return FetchResult.verificationRequired("账号登录凭证不可用");
        }

        XianyuApiCallUtils.ApiCallResult apiResult;
        try {
            apiResult = apiCallUtils.callApiWithRetry(
                    accountId, DETAIL_API, Map.of("itemId", itemId), cookieText);
        } catch (Exception e) {
            log.warn("商品详情 API 调用异常，转只读页面兜底: accountId={}, itemId={}, errorType={}",
                    accountId, itemId, e.getClass().getSimpleName());
            apiResult = new XianyuApiCallUtils.ApiCallResult(
                    false, null, "平台详情接口调用异常", false);
        }
        if (apiResult != null && apiResult.isSuccess()) {
            String normalized = normalizeAndValidate(apiResult.getResponse(), itemId);
            if (normalized != null) {
                return FetchResult.success(Source.API, normalized);
            }
            log.warn("商品详情 API 返回的商品身份不匹配，转只读页面兜底: accountId={}, itemId={}",
                    accountId, itemId);
        } else {
            log.warn("商品详情 API 失败，转只读页面兜底: accountId={}, itemId={}, reason={}",
                    accountId, itemId, safeApiMessage(apiResult));
        }

        if (!browserFallbackEnabled) {
            return failureFromApi(apiResult, "只读页面兜底已关闭");
        }

        FetchResult browserResult = fetchFromPage(accountId, itemId, cookieText);
        if (browserResult.status() == Status.UNAVAILABLE && isVerificationFailure(apiResult)) {
            return FetchResult.verificationRequired(
                    "账号登录状态失效，请前往连接管理完成扫码或账号验证");
        }
        return browserResult;
    }

    private FetchResult fetchFromPage(Long accountId, String itemId, String cookieText) {
        ReentrantLock lock = accountLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return FetchResult.busy("该账号已有商品详情只读补采任务，请稍后重试");
        }
        int effectiveTimeoutMs = effectiveFallbackTimeoutMs();
        try (BrowserContext context = playwrightManager.createContext(accountId)) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.setDefaultTimeout(Math.min(effectiveTimeoutMs, 15_000));
            Response response;
            try {
                response = page.waitForResponse(
                        candidate -> isDetailResponse(candidate, itemId),
                        new Page.WaitForResponseOptions().setTimeout(effectiveTimeoutMs),
                        () -> page.navigate(ITEM_URL + itemId,
                                new Page.NavigateOptions()
                                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                        .setTimeout(effectiveTimeoutMs)));
            } catch (PlaywrightException e) {
                if (pageRequiresVerification(page)) {
                    return FetchResult.verificationRequired("平台要求重新扫码或完成账号验证");
                }
                return FetchResult.unavailable("商品页未捕获到有效详情响应: " + safeMessage(e));
            }

            if (response == null || !response.ok()) {
                return FetchResult.unavailable("商品页详情响应不可用");
            }
            String normalized = normalizeAndValidate(response.text(), itemId);
            if (normalized == null) {
                if (pageRequiresVerification(page)) {
                    return FetchResult.verificationRequired("平台要求重新扫码或完成账号验证");
                }
                return FetchResult.unavailable("商品页返回的详情与目标商品不一致");
            }
            playwrightManager.persistStorageState(accountId, context);
            return FetchResult.success(Source.PLATFORM_PAGE, normalized);
        } catch (Exception e) {
            log.warn("商品详情只读页面兜底失败: accountId={}, itemId={}, reason={}",
                    accountId, itemId, safeMessage(e));
            return FetchResult.unavailable("只读页面兜底失败: " + safeMessage(e));
        } finally {
            lock.unlock();
        }
    }

    boolean isDetailResponse(Response response, String itemId) {
        if (response == null || response.url() == null
                || !response.url().toLowerCase(Locale.ROOT).contains(DETAIL_API)) {
            return false;
        }
        try {
            String rawQuery = URI.create(response.url()).getRawQuery();
            if (rawQuery == null) {
                return false;
            }
            for (String parameter : rawQuery.split("&")) {
                String[] pair = parameter.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2
                        ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if ("itemId".equals(key) && itemId.equals(value)) {
                    return true;
                }
                if ("data".equals(key)) {
                    JsonNode data = objectMapper.readTree(value);
                    if (itemId.equals(data.path("itemId").asText(""))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("忽略无法验证商品 ID 的详情候选响应: {}", e.getClass().getSimpleName());
        }
        return false;
    }

    String normalizeAndValidate(String responseText, String itemId) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        try {
            String normalized = unwrapJsonp(responseText.trim());
            JsonNode root = objectMapper.readTree(normalized);
            JsonNode ret = root.path("ret");
            if (!ret.isArray() || ret.isEmpty()
                    || !ret.get(0).asText("").startsWith("SUCCESS")) {
                return null;
            }
            JsonNode item = root.path("data").path("itemDO");
            if (!item.isObject()) {
                return null;
            }
            String responseItemId = item.path("itemId").asText("");
            if (responseItemId.isBlank()) {
                responseItemId = item.path("id").asText("");
            }
            return itemId.equals(responseItemId) ? normalized : null;
        } catch (Exception e) {
            log.debug("商品详情响应不是可验证 JSON: errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String unwrapJsonp(String value) {
        if (value.startsWith("{") || value.startsWith("[")) {
            return value;
        }
        int start = value.indexOf('(');
        int end = value.lastIndexOf(')');
        if (start > 0 && end > start) {
            return value.substring(start + 1, end).trim();
        }
        return value;
    }

    private FetchResult failureFromApi(XianyuApiCallUtils.ApiCallResult apiResult, String fallbackMessage) {
        if (isVerificationFailure(apiResult)) {
            return FetchResult.verificationRequired(safeApiMessage(apiResult));
        }
        String message = safeApiMessage(apiResult);
        return FetchResult.unavailable(message == null || message.isBlank() ? fallbackMessage : message);
    }

    private boolean isVerificationFailure(XianyuApiCallUtils.ApiCallResult apiResult) {
        if (apiResult == null) {
            return false;
        }
        String message = apiResult.getErrorMessage() == null ? "" : apiResult.getErrorMessage();
        String response = apiResult.getResponse() == null ? "" : apiResult.getResponse();
        return apiResult.isTokenExpired()
                || message.contains("验证") || message.contains("登录") || message.contains("令牌")
                || response.contains("FAIL_SYS_USER_VALIDATE")
                || response.contains("FAIL_SYS_SESSION_EXPIRED");
    }

    private boolean pageRequiresVerification(Page page) {
        try {
            String url = page.url() == null ? "" : page.url().toLowerCase(Locale.ROOT);
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
        if (!cookies.isEmpty()) {
            context.addCookies(cookies);
        }
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        String firstLine = redactSecrets(message.lines().findFirst().orElse(message));
        return firstLine.length() <= 240 ? firstLine : firstLine.substring(0, 240);
    }

    int effectiveFallbackTimeoutMs() {
        return Math.max(MIN_FALLBACK_TIMEOUT_MS,
                Math.min(MAX_FALLBACK_TIMEOUT_MS, browserFallbackTimeoutMs));
    }

    private String safeApiMessage(XianyuApiCallUtils.ApiCallResult apiResult) {
        if (apiResult == null) {
            return "平台详情接口未返回结果";
        }
        String message = apiResult.getErrorMessage();
        if (message == null || message.isBlank()) {
            return "平台详情接口暂不可用";
        }
        String safe = redactSecrets(message.lines().findFirst().orElse(message));
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    private String redactSecrets(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)", "$1[REDACTED]")
                .replaceAll("(?i)((?:cookie|token|access_token|_m_h5_tk|_m_h5_tk_enc|sid|sign)"
                                + "\\s*[:=]\\s*)([^\\s,;&]+)",
                        "$1[REDACTED]");
    }

    public enum Source {
        API,
        PLATFORM_PAGE
    }

    public enum Status {
        SUCCESS,
        VERIFICATION_REQUIRED,
        BUSY,
        UNAVAILABLE
    }

    public record FetchResult(Status status, Source source, String response, String errorMessage) {
        static FetchResult success(Source source, String response) {
            return new FetchResult(Status.SUCCESS, source, response, null);
        }

        static FetchResult verificationRequired(String message) {
            return new FetchResult(Status.VERIFICATION_REQUIRED, null, null,
                    message == null || message.isBlank() ? "平台要求重新验证账号" : message);
        }

        static FetchResult unavailable(String message) {
            return new FetchResult(Status.UNAVAILABLE, null, null,
                    message == null || message.isBlank() ? "商品详情暂不可用" : message);
        }

        static FetchResult busy(String message) {
            return new FetchResult(Status.BUSY, null, null,
                    message == null || message.isBlank() ? "该账号正在补采，请稍后重试" : message);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS && response != null;
        }
    }
}
