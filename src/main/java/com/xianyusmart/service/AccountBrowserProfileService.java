package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.playwright.options.ColorScheme;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuDeviceProfile;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuDeviceProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Creates and applies a stable browser runtime profile per Xianyu account.
 * The advertised desktop platform is independent of the container host OS.
 */
@Slf4j
@Service
public class AccountBrowserProfileService {

    private static final String DEFAULT_BROWSER_VERSION = "149.0.7827.55";
    private static final int[][] VIEWPORTS = {
            {1365, 768}, {1440, 900}, {1536, 864}, {1600, 900}
    };

    private final XianyuDeviceProfileMapper profileMapper;
    private final XianyuAccountMapper accountMapper;

    public AccountBrowserProfileService(XianyuDeviceProfileMapper profileMapper,
                                        XianyuAccountMapper accountMapper) {
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public XianyuDeviceProfile getOrCreate(Long accountId) {
        if (accountId == null || accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("账号不存在或不属于当前租户");
        }
        XianyuDeviceProfile existing = find(accountId);
        if (existing != null) {
            return existing;
        }

        XianyuDeviceProfile created = newProfile(accountId);
        try {
            profileMapper.insert(created);
            return created;
        } catch (DuplicateKeyException concurrentInsert) {
            XianyuDeviceProfile winner = find(accountId);
            if (winner != null) {
                return winner;
            }
            throw concurrentInsert;
        }
    }

    static XianyuDeviceProfile newProfile(Long accountId) {
        XianyuDeviceProfile created = new XianyuDeviceProfile();
        created.setXianyuAccountId(accountId);
        created.setProfileKey(UUID.randomUUID().toString());
        created.setProfileType("DESKTOP_WEB");
        created.setPlatform(platformForAccount(accountId));
        created.setBrowserVersion(DEFAULT_BROWSER_VERSION);
        created.setLocale("zh-CN");
        created.setTimezoneId("Asia/Shanghai");
        int[] viewport = viewportForAccount(accountId);
        created.setViewportWidth(viewport[0]);
        created.setViewportHeight(viewport[1]);
        created.setDeviceScaleFactor(BigDecimal.ONE);
        created.setColorScheme("light");
        created.setStatus(1);
        return created;
    }

    public XianyuDeviceProfile find(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return profileMapper.selectOne(new LambdaQueryWrapper<XianyuDeviceProfile>()
                .eq(XianyuDeviceProfile::getXianyuAccountId, accountId));
    }

    public Browser.NewContextOptions contextOptions(Long accountId, String browserVersion) {
        XianyuDeviceProfile profile = getOrCreate(accountId);
        updateBrowserVersion(profile, browserVersion);
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setUserAgent(userAgent(profile.getPlatform(), browserVersion))
                .setLocale(profile.getLocale())
                .setTimezoneId(profile.getTimezoneId())
                .setViewportSize(profile.getViewportWidth(), profile.getViewportHeight())
                .setScreenSize(profile.getViewportWidth(), profile.getViewportHeight())
                .setDeviceScaleFactor(profile.getDeviceScaleFactor().doubleValue())
                .setColorScheme("dark".equalsIgnoreCase(profile.getColorScheme())
                        ? ColorScheme.DARK : ColorScheme.LIGHT);
        if (profile.getBrowserStorageState() != null && !profile.getBrowserStorageState().isBlank()) {
            options.setStorageState(profile.getBrowserStorageState());
        }
        return options;
    }

    public void persistStorageState(Long accountId, BrowserContext context) {
        if (accountId == null || context == null) {
            return;
        }
        try {
            String state = context.storageState(
                    new BrowserContext.StorageStateOptions().setIndexedDB(true));
            XianyuDeviceProfile profile = getOrCreate(accountId);
            profile.setBrowserStorageState(state);
            profile.setStorageStateUpdatedTime(LocalDateTime.now());
            profileMapper.updateById(profile);
        } catch (Exception e) {
            log.warn("【账号{}】保存浏览器状态失败: {}", accountId, e.getClass().getSimpleName());
        }
    }

    public void decorate(XianyuAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        XianyuDeviceProfile profile = getOrCreate(account.getId());
        account.setRuntimeProfileKey(profile.getProfileKey());
        account.setRuntimeProfileType(profile.getProfileType());
        account.setRuntimePlatform(profile.getPlatform());
        account.setRuntimeViewport(profile.getViewportWidth() + "x" + profile.getViewportHeight());
        account.setRuntimeBrowserVersion(normalizedVersion(profile.getBrowserVersion()));
        account.setBrowserStateReady(profile.getBrowserStorageState() != null
                && !profile.getBrowserStorageState().isBlank());
    }

    public String userAgentForAccount(Long accountId) {
        XianyuDeviceProfile profile = getOrCreate(accountId);
        return userAgent(profile.getPlatform(), profile.getBrowserVersion());
    }

    public String clientHintPlatformForAccount(Long accountId) {
        return clientHintPlatform(getOrCreate(accountId).getPlatform());
    }

    public static String clientHintPlatform(String platform) {
        return "MACOS".equals(platform) ? "macOS" : "Windows";
    }

    public Map<String, String> headersForAccount(Long accountId) {
        XianyuDeviceProfile profile = getOrCreate(accountId);
        return desktopHeaders(profile.getPlatform(), profile.getBrowserVersion());
    }

    public static Map<String, String> desktopHeaders(String platform, String version) {
        String major = normalizedVersion(version).split("\\.", 2)[0];
        return Map.of("User-Agent", userAgent(platform, version),
                "Sec-Ch-Ua-Platform", "\"" + clientHintPlatform(platform) + "\"",
                "Sec-Ch-Ua-Mobile", "?0",
                "Sec-Ch-Ua", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"" + major
                        + "\", \"Chromium\";v=\"" + major + "\"");
    }

    /** Apply native Chromium emulation before callers navigate a new page. */
    public BrowserContext createContext(Browser browser, Long accountId) {
        BrowserContext context = browser.newContext(contextOptions(accountId, browser.version()));
        XianyuDeviceProfile profile = getOrCreate(accountId);
        applyDesktopPlatform(context, profile.getPlatform(), browser.version());
        return context;
    }

    public static void applyDesktopPlatform(BrowserContext context, String platform, String version) {
        JsonObject override = userAgentOverride(platform, version);
        context.setExtraHTTPHeaders(desktopHeaders(platform, version));
        context.onPage(page -> context.newCDPSession(page).send("Emulation.setUserAgentOverride", override));
        context.pages().forEach(page -> context.newCDPSession(page).send("Emulation.setUserAgentOverride", override));
    }

    static JsonObject userAgentOverride(String platform, String version) {
        String fullVersion = normalizedVersion(version);
        String major = fullVersion.split("\\.", 2)[0];
        Map<String, Object> metadata = Map.of(
                "brands", List.of(Map.of("brand", "Chromium", "version", major),
                        Map.of("brand", "Google Chrome", "version", major),
                        Map.of("brand", "Not(A:Brand", "version", "99")),
                "fullVersionList", List.of(Map.of("brand", "Chromium", "version", fullVersion),
                        Map.of("brand", "Google Chrome", "version", fullVersion),
                        Map.of("brand", "Not(A:Brand", "version", "99.0.0.0")),
                "fullVersion", fullVersion, "platform", clientHintPlatform(platform),
                "platformVersion", "MACOS".equals(platform) ? "10.15.7" : "10.0.0",
                "architecture", "x86", "bitness", "64", "model", "", "mobile", false,
                "wow64", false);
        return new Gson().toJsonTree(Map.of("userAgent", userAgent(platform, version),
                "acceptLanguage", "zh-CN,zh;q=0.9", "platform", "MACOS".equals(platform) ? "MacIntel" : "Win32",
                "userAgentMetadata", metadata)).getAsJsonObject();
    }

    public String browserMajorForAccount(Long accountId) {
        String version = getOrCreate(accountId).getBrowserVersion();
        return version != null && version.matches("\\d+(?:\\.\\d+){0,3}")
                ? version.split("\\.", 2)[0] : DEFAULT_BROWSER_VERSION.split("\\.", 2)[0];
    }

    public static String defaultDesktopUserAgent() {
        return userAgent("WINDOWS", null);
    }

    static int[] viewportFor(String profileKey) {
        return VIEWPORTS[Math.floorMod(profileKey.hashCode(), VIEWPORTS.length)].clone();
    }

    static int[] viewportForAccount(Long accountId) {
        long stableId = accountId == null ? 1L : accountId;
        return VIEWPORTS[Math.floorMod(stableId - 1, VIEWPORTS.length)].clone();
    }

    static String platformForAccount(Long accountId) {
        return accountId != null && accountId % 2 == 0 ? "MACOS" : "WINDOWS";
    }

    private static String normalizedVersion(String browserVersion) {
        String version = browserVersion == null ? "" : browserVersion.trim();
        if (!version.matches("\\d+(?:\\.\\d+){0,3}")) {
            version = DEFAULT_BROWSER_VERSION;
        }
        return version;
    }

    public static String userAgent(String platform, String browserVersion) {
        String version = normalizedVersion(browserVersion);
        String osToken = switch (platform == null ? "" : platform) {
            case "MACOS" -> "Macintosh; Intel Mac OS X 10_15_7";
            case "WINDOWS" -> "Windows NT 10.0; Win64; x64";
            default -> "Windows NT 10.0; Win64; x64";
        };
        return "Mozilla/5.0 (" + osToken + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + version + " Safari/537.36";
    }

    private void updateBrowserVersion(XianyuDeviceProfile profile, String browserVersion) {
        if (browserVersion == null || browserVersion.isBlank()
                || browserVersion.equals(profile.getBrowserVersion())) {
            return;
        }
        profile.setBrowserVersion(browserVersion);
        profileMapper.updateById(profile);
    }
}
