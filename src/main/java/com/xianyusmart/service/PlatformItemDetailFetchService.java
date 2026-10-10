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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 商品详情的统一只读获取链路。默认仅使用带令牌刷新的 MTop API；明确验证挑战、
 * 过期或忙碌时立即停止。仅显式启用页面兜底后，普通未知失败才使用账号隔离页面
 * 捕获同一个详情接口。
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
    // Metadata only, never a navigation allowlist or a reconstructed verification link.
    private static final Set<String> RECOVERY_HOSTS = Set.of("www.goofish.com",
            "passport.goofish.com", "h5api.m.goofish.com", "login.goofish.com", "login.taobao.com");
    private static final Set<String> RECOVERY_STATIC_PATHS = Set.of("/", "/_____tmd_____/punish",
            "/newlogin/login.do");

    private final PlaywrightManager playwrightManager;
    private final XianyuApiCallUtils apiCallUtils;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ItemVerificationEntryService verificationEntries;
    // 锁按已授权账号长期复用。不要在 unlock 后移除，否则新请求可能创建第二把锁并并行进入。
    private final ConcurrentMap<Long, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    @Value("${xianyu.item-detail.browser-fallback-enabled:false}")
    private boolean browserFallbackEnabled = false;

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
            log.warn("商品详情 API 调用异常: accountId={}, itemId={}, errorType={}",
                    accountId, itemId, e.getClass().getSimpleName());
            apiResult = new XianyuApiCallUtils.ApiCallResult(
                    false, null, "平台详情接口调用异常", false);
        }
        if (verificationEntries != null) {
            verificationEntries.observe(accountId, itemId, apiResult == null ? null : apiResult.getResponse());
        }
        if (apiResult != null && apiResult.isSuccess()) {
            String normalized = normalizeAndValidate(apiResult.getResponse(), itemId);
            if (normalized != null) {
                return FetchResult.success(Source.API, normalized);
            }
            log.warn("商品详情 API 返回的商品身份不匹配: accountId={}, itemId={}",
                    accountId, itemId);
        }
        RecoveryInfo recovery = recoveryInfo(apiResult);
        if (recovery.kind() != FailureKind.UNKNOWN) {
            // A challenge is a stop condition, not an invitation to try another surface/API.
            log.warn("商品详情请求已阻断: accountId={}, itemId={}, kind={}, code={}, entryPresence={}, officialHost={}, staticPath={}",
                    accountId, itemId, recovery.kind(), recovery.platformCode(), recovery.entryPresence(),
                    recovery.officialHost(), recovery.staticPath());
            return classifiedFailure(recovery);
        }
        if (!browserFallbackEnabled) {
            log.warn("商品详情 API 未取得可验证响应，按接口模式停止: accountId={}, itemId={}",
                    accountId, itemId);
            return FetchResult.unavailable(safeApiMessage(apiResult));
        }
        log.warn("商品详情 API 未取得可验证响应，转显式启用的只读页面兜底: accountId={}, itemId={}",
                accountId, itemId);
        return fetchFromPage(accountId, itemId, cookieText);
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
                    return FetchResult.verificationRequired("商品页要求登录或验证，请仅按官方页面提示手工完成；未证明整个账号凭证过期");
                }
                return FetchResult.unavailable("商品页未捕获到有效详情响应: " + safeMessage(e));
            }

            if (response == null || !response.ok()) {
                return FetchResult.unavailable("商品页详情响应不可用");
            }
            String responseText = response.text();
            if (verificationEntries != null) verificationEntries.observe(accountId, itemId, responseText);
            RecoveryInfo recovery = recoveryInfo(new XianyuApiCallUtils.ApiCallResult(
                    false, responseText, null, false));
            if (recovery.kind() != FailureKind.UNKNOWN) {
                return classifiedFailure(recovery);
            }
            String normalized = normalizeAndValidate(responseText, itemId);
            if (normalized == null) {
                if (pageRequiresVerification(page)) {
                    return FetchResult.verificationRequired("商品页要求登录或验证，请仅按官方页面提示手工完成；未证明整个账号凭证过期");
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

    RecoveryInfo recoveryInfo(XianyuApiCallUtils.ApiCallResult result) {
        JsonNode root = null;
        if (result != null && result.getResponse() != null) {
            try { root = objectMapper.readTree(unwrapJsonp(result.getResponse().trim())); }
            catch (Exception ignored) { /* Raw failure content must not enter logs/results. */ }
        }
        String ret = "";
        if (root != null && root.path("ret").isArray() && !root.path("ret").isEmpty()
                && root.path("ret").get(0).isTextual()) {
            ret = root.path("ret").get(0).asText();
        } else if (result != null && result.getErrorMessage() != null) {
            ret = result.getErrorMessage();
        }
        String code = ret.split("::", 2)[0].trim();
        FailureKind kind = switch (code) {
            case "FAIL_SYS_USER_VALIDATE" -> FailureKind.REQUEST_VERIFICATION;
            case "FAIL_SYS_TOKEN_EXOIRED", "FAIL_SYS_TOKEN_EXPIRED", "FAIL_SYS_SESSION_EXPIRED" -> FailureKind.TOKEN_EXPIRED;
            case "FAIL_SYS_TRAFFIC_LIMIT", "FAIL_SYS_REQUEST_BUSY" -> FailureKind.PLATFORM_BUSY;
            default -> result != null && result.isTokenExpired() ? FailureKind.TOKEN_EXPIRED : FailureKind.UNKNOWN;
        };
        String safeCode = switch (code) {
            case "FAIL_SYS_USER_VALIDATE", "FAIL_SYS_TOKEN_EXOIRED", "FAIL_SYS_TOKEN_EXPIRED",
                    "FAIL_SYS_SESSION_EXPIRED", "FAIL_SYS_TRAFFIC_LIMIT", "FAIL_SYS_REQUEST_BUSY" -> code;
            default -> null;
        };
        EntryPresence presence = root == null ? EntryPresence.UNKNOWN : EntryPresence.ABSENT;
        String host = null, path = null;
        JsonNode entry = root == null ? null : root.path("data").get("url");
        if (entry != null && !entry.isNull() && (!entry.isTextual() || !entry.asText().isBlank())) {
            presence = EntryPresence.PRESENT;
            if (entry.isTextual() && entry.asText().length() <= 8192) {
                try {
                    URI uri = URI.create(entry.asText());
                    String candidate = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                    if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                            && (uri.getPort() == -1 || uri.getPort() == 443) && RECOVERY_HOSTS.contains(candidate)) {
                        host = candidate;
                        if (RECOVERY_STATIC_PATHS.contains(uri.getRawPath())) path = uri.getRawPath();
                    }
                } catch (IllegalArgumentException ignored) { /* Keep presence only. */ }
            }
        }
        return new RecoveryInfo(kind, safeCode, presence, host, path);
    }

    private FetchResult classifiedFailure(RecoveryInfo recovery) {
        String message = switch (recovery.kind()) {
            case REQUEST_VERIFICATION -> "本次商品详情请求需要平台安全验证（FAIL_SYS_USER_VALIDATE），未证明整个账号凭证过期。请仅按官方页面提示手工完成；不要反复扫码";
            case TOKEN_EXPIRED -> "商品详情接口令牌或会话已明确过期，请在连接管理按正常流程更新当前账号凭证";
            case PLATFORM_BUSY -> "平台商品详情请求忙碌，请稍后重试";
            case UNKNOWN -> "商品详情接口暂不可用，失败原因尚未确认";
        };
        if (recovery.kind() == FailureKind.REQUEST_VERIFICATION) {
            message += switch (recovery.entryPresence()) {
                case UNKNOWN -> "；验证入口存在性未知";
                case ABSENT -> "；本次响应未提供验证入口";
                case PRESENT -> "；本次响应提供验证入口，完整链接未公开";
            };
            if (recovery.officialHost() != null) message += "；官方host=" + recovery.officialHost();
            if (recovery.staticPath() != null) message += "；静态path=" + recovery.staticPath();
        }
        return new FetchResult(recovery.kind() == FailureKind.PLATFORM_BUSY ? Status.BUSY
                : Status.VERIFICATION_REQUIRED, null, null, message, recovery);
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
        // Unknown upstream text can contain signed URLs or unlabelled credentials.
        return "平台详情接口暂不可用，失败原因尚未确认";
    }

    private String redactSecrets(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)https?://[^\\s\"<>]+", "[URL_OMITTED]")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)", "$1[REDACTED]")
                .replaceAll("(?i)((?:cookie|token|access_token|_m_h5_tk|_m_h5_tk_enc|sid|sign|ticket|session_id)"
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

    public enum FailureKind { REQUEST_VERIFICATION, TOKEN_EXPIRED, PLATFORM_BUSY, UNKNOWN }
    public enum EntryPresence { UNKNOWN, ABSENT, PRESENT }
    public record RecoveryInfo(FailureKind kind, String platformCode, EntryPresence entryPresence,
                               String officialHost, String staticPath) { }

    public record FetchResult(Status status, Source source, String response, String errorMessage,
                              RecoveryInfo recoveryInfo) {
        public FetchResult(Status status, Source source, String response, String errorMessage) {
            this(status, source, response, errorMessage, null);
        }
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
