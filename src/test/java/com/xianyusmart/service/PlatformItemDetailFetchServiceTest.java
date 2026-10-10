package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformItemDetailFetchServiceTest {

    private XianyuApiCallUtils apiCallUtils;
    private PlaywrightManager playwrightManager;
    private PlatformItemDetailFetchService service;

    @BeforeEach
    void setUp() {
        apiCallUtils = mock(XianyuApiCallUtils.class);
        playwrightManager = mock(PlaywrightManager.class);
        service = new PlatformItemDetailFetchService(
                playwrightManager, apiCallUtils, new ObjectMapper());
    }

    @Test
    void forwardsRealChallengeOnlyToEphemeralCaptureAndStops() {
        ItemVerificationEntryService entries = mock(ItemVerificationEntryService.class);
        ReflectionTestUtils.setField(service, "verificationEntries", entries);
        String raw = "{\"ret\":[\"FAIL_SYS_USER_VALIDATE::verify\"],\"data\":{\"url\":\"https://h5api.m.goofish.com/_____tmd_____/punish?ticket=SECRET_SENTINEL\"}}";
        stubApiFailure(raw, null, false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        verify(entries).observe(7L, "12345678", raw);
        verifyNoInteractions(playwrightManager);
        assertFalse(result.toString().contains("SECRET_SENTINEL"));
        assertNull(result.response());
    }

    @Test
    void acceptsOnlySuccessfulResponseForRequestedItem() {
        String valid = response("12345678", "SUCCESS::调用成功");

        assertEquals(valid, service.normalizeAndValidate(valid, "12345678"));
        assertNull(service.normalizeAndValidate(valid, "87654321"));
        assertNull(service.normalizeAndValidate(response("12345678", "FAIL_SYS_USER_VALIDATE::验证"),
                "12345678"));
    }

    @Test
    void unwrapsJsonpBeforeValidatingIdentity() {
        String json = response("12345678", "SUCCESS::调用成功");

        assertEquals(json, service.normalizeAndValidate("mtopjsonp1(" + json + ")", "12345678"));
    }

    @Test
    void returnsApiEvidenceWithoutStartingBrowserWhenApiIsValid() {
        String json = response("12345678", "SUCCESS::调用成功");
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "cookie=ok"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, json, null, false));

        PlatformItemDetailFetchService.FetchResult result =
                service.fetch(7L, "12345678", "cookie=ok");

        assertTrue(result.isSuccess());
        assertEquals(PlatformItemDetailFetchService.Source.API, result.source());
        assertEquals(json, result.response());
    }

    @Test
    void missingCookieIsReportedAsVerificationRequired() {
        PlatformItemDetailFetchService.FetchResult result = service.fetch(7L, "12345678", "");

        assertEquals(PlatformItemDetailFetchService.Status.VERIFICATION_REQUIRED, result.status());
        assertTrue(result.errorMessage().contains("凭证"));
    }

    @Test
    void candidateResponseRequiresExactRequestedItemId() {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(
                "https://h5api.m.goofish.com/h5/mtop.taobao.idle.pc.detail/1.0/"
                        + "?data=%7B%22itemId%22%3A%22123456789%22%7D");

        assertFalse(service.isDetailResponse(response, "12345678"));
        assertTrue(service.isDetailResponse(response, "123456789"));
    }

    @Test
    void clampsUnsafeFallbackTimeoutConfiguration() {
        ReflectionTestUtils.setField(service, "browserFallbackTimeoutMs", -1);
        assertEquals(5_000, service.effectiveFallbackTimeoutMs());

        ReflectionTestUtils.setField(service, "browserFallbackTimeoutMs", Integer.MAX_VALUE);
        assertEquals(120_000, service.effectiveFallbackTimeoutMs());
    }

    @Test
    void defaultApiOnlyReturnsRedactedFailureWithoutOpeningPage() {
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, null, "token=secret cookie=private", false));

        PlatformItemDetailFetchService.FetchResult result =
                service.fetch(7L, "12345678", "sid=abc");

        assertEquals(PlatformItemDetailFetchService.Status.UNAVAILABLE, result.status());
        assertFalse(result.errorMessage().contains("secret"));
        assertFalse(result.errorMessage().contains("private"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void verificationFailureReturnsActionableMessageWithoutApiSecrets() {
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, null, "token=api-secret 需要验证", true));
        when(playwrightManager.createContext(7L))
                .thenThrow(new IllegalStateException("cookie=page-secret"));

        PlatformItemDetailFetchService.FetchResult result =
                service.fetch(7L, "12345678", "sid=abc");

        assertEquals(PlatformItemDetailFetchService.Status.VERIFICATION_REQUIRED, result.status());
        assertTrue(result.errorMessage().contains("连接管理"));
        assertFalse(result.errorMessage().contains("api-secret"));
        assertFalse(result.errorMessage().contains("page-secret"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void userValidateStopsAllFallbackAndRetainsOnlySafeRecoveryMetadata() throws Exception {
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "ret", java.util.List.of("FAIL_SYS_USER_VALIDATE::RGV587_ERROR::sensitive-ret-detail"),
                "data", java.util.Map.of("url", "https://h5api.m.goofish.com/_____tmd_____/punish"
                        + "?ticket=private-ticket&sign=private-sign#private-fragment")));
        stubApiFailure(json, "登录验证 token=private-api-token", false);

        var result = service.fetch(7L, "12345678", "sid=abc");

        assertEquals(PlatformItemDetailFetchService.Status.VERIFICATION_REQUIRED, result.status());
        assertEquals(PlatformItemDetailFetchService.FailureKind.REQUEST_VERIFICATION, result.recoveryInfo().kind());
        assertEquals("FAIL_SYS_USER_VALIDATE", result.recoveryInfo().platformCode());
        assertEquals(PlatformItemDetailFetchService.EntryPresence.PRESENT, result.recoveryInfo().entryPresence());
        assertEquals("h5api.m.goofish.com", result.recoveryInfo().officialHost());
        assertEquals("/_____tmd_____/punish", result.recoveryInfo().staticPath());
        assertNull(result.response());
        assertFalse(result.errorMessage().contains("登录状态失效"));
        for (String secret : java.util.List.of("private-ticket", "private-sign", "private-fragment",
                "private-api-token", "sensitive-ret-detail", "https://", "?", "#")) {
            assertFalse(result.toString().contains(secret));
        }
        verifyNoInteractions(playwrightManager);
        verify(apiCallUtils, times(1)).callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc");
    }

    @Test
    void userValidateWithoutResponseEntryDoesNotInventVerificationUrl() {
        stubApiFailure("{\"ret\":[\"FAIL_SYS_USER_VALIDATE::验证\"],\"data\":{}}", "登录", false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        assertEquals(PlatformItemDetailFetchService.EntryPresence.ABSENT, result.recoveryInfo().entryPresence());
        assertNull(result.recoveryInfo().officialHost());
        assertNull(result.recoveryInfo().staticPath());
        assertTrue(result.errorMessage().contains("未提供验证入口"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void explicitValidationCodeWithoutRawResponseLeavesEntryPresenceUnknown() {
        stubApiFailure(null, "FAIL_SYS_USER_VALIDATE::验证", false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        assertEquals(PlatformItemDetailFetchService.EntryPresence.UNKNOWN, result.recoveryInfo().entryPresence());
        assertTrue(result.errorMessage().contains("存在性未知"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void explicitTokenAndSessionExpirationNeverBecomeRequestChallengeOrBrowserRetry() {
        for (String code : java.util.List.of("FAIL_SYS_TOKEN_EXOIRED", "FAIL_SYS_TOKEN_EXPIRED", "FAIL_SYS_SESSION_EXPIRED")) {
            stubApiFailure("{\"ret\":[\"" + code + "::过期\"]}", "未分类", false);
            var result = service.fetch(7L, "12345678", "sid=abc");
            assertEquals(PlatformItemDetailFetchService.FailureKind.TOKEN_EXPIRED, result.recoveryInfo().kind());
            assertTrue(result.errorMessage().contains("明确过期"));
            assertFalse(result.errorMessage().contains("FAIL_SYS_USER_VALIDATE"));
        }
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void genericLoginTokenOrVerificationWordsDoNotProveExpiration() {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", false);
        stubApiFailure("{\"ret\":[\"FAIL_BIZ_UNKNOWN::验证登录令牌\"],\"data\":{}}",
                "验证登录令牌 ticket=private https://example.invalid/?secret=private", false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        assertEquals(PlatformItemDetailFetchService.Status.UNAVAILABLE, result.status());
        assertNull(result.recoveryInfo());
        assertFalse(result.errorMessage().contains("private"));
        assertFalse(result.errorMessage().contains("过期"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void recognizedBusyResponseDoesNotStartBrowserOrPretendCredentialFailure() {
        stubApiFailure("{\"ret\":[\"FAIL_SYS_TRAFFIC_LIMIT::busy\"]}", "忙", false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        assertEquals(PlatformItemDetailFetchService.Status.BUSY, result.status());
        assertEquals(PlatformItemDetailFetchService.FailureKind.PLATFORM_BUSY, result.recoveryInfo().kind());
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void unapprovedHostsSchemesCredentialsPortsAndDynamicPathsNeverLeak() throws Exception {
        for (String url : java.util.List.of("https://www.goofish.com.evil.invalid/_____tmd_____/punish?ticket=secret",
                "http://www.goofish.com/_____tmd_____/punish?ticket=secret",
                "https://secret@www.goofish.com/_____tmd_____/punish",
                "https://www.goofish.com:444/_____tmd_____/punish?ticket=secret",
                "https://secret.taobao.com/secret?ticket=secret", "malformed-secret")) {
            stubApiFailure(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "ret", java.util.List.of("FAIL_SYS_USER_VALIDATE::verify"), "data", java.util.Map.of("url", url))), null, false);
            var result = service.fetch(7L, "12345678", "sid=abc");
            assertEquals(PlatformItemDetailFetchService.EntryPresence.PRESENT, result.recoveryInfo().entryPresence());
            assertNull(result.recoveryInfo().officialHost());
            assertNull(result.recoveryInfo().staticPath());
            assertFalse(result.toString().contains("secret"));
        }
        stubApiFailure("{\"ret\":[\"FAIL_SYS_USER_VALIDATE\"],\"data\":{\"url\":\"https://www.goofish.com/private-dynamic-path?ticket=secret\"}}", null, false);
        var result = service.fetch(7L, "12345678", "sid=abc");
        assertEquals("www.goofish.com", result.recoveryInfo().officialHost());
        assertNull(result.recoveryInfo().staticPath());
        assertFalse(result.toString().contains("private-dynamic-path"));
        verifyNoInteractions(playwrightManager);
    }

    @Test
    void validationWordsInsideDataDoNotOverrideStructuredUnknownRetCode() {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", false);
        stubApiFailure("{\"ret\":[\"FAIL_BIZ_UNKNOWN\"],\"data\":{\"desc\":\"FAIL_SYS_USER_VALIDATE\"}}", null, false);
        assertEquals(PlatformItemDetailFetchService.Status.UNAVAILABLE,
                service.fetch(7L, "12345678", "sid=abc").status());
    }

    private void stubApiFailure(String response, String message, boolean expired) {
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, response, message, expired));
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiFailureUsesReadOnlyAccountIsolatedPageCapture() {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", true);
        String json = response("12345678", "SUCCESS::调用成功");
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "网络波动", false));
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Response response = mock(Response.class);
        when(playwrightManager.createContext(7L)).thenReturn(context);
        when(context.newPage()).thenReturn(page);
        when(response.url()).thenReturn(
                "https://h5api.m.goofish.com/h5/mtop.taobao.idle.pc.detail/1.0/?data=%7B%22itemId%22%3A%2212345678%22%7D");
        when(response.ok()).thenReturn(true);
        when(response.text()).thenReturn(json);
        when(page.waitForResponse(
                org.mockito.ArgumentMatchers.<java.util.function.Predicate<Response>>any(),
                org.mockito.ArgumentMatchers.any(Page.WaitForResponseOptions.class),
                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return response;
                });

        PlatformItemDetailFetchService.FetchResult result =
                service.fetch(7L, "12345678", "sid=abc");

        assertTrue(result.isSuccess());
        assertEquals(PlatformItemDetailFetchService.Source.PLATFORM_PAGE, result.source());
        verify(context).addCookies(org.mockito.ArgumentMatchers.anyList());
        verify(page).navigate(org.mockito.ArgumentMatchers.contains("12345678"),
                org.mockito.ArgumentMatchers.any(Page.NavigateOptions.class));
        verify(playwrightManager).persistStorageState(7L, context);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameAccountReturnsBusyThenReusesReleasedLock() throws Exception {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", true);
        String json = response("12345678", "SUCCESS::调用成功");
        when(apiCallUtils.callApiWithRetry(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(PlatformItemDetailFetchService.DETAIL_API),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq("sid=abc")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "网络波动", false));
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Response response = detailResponse("12345678", json);
        when(playwrightManager.createContext(7L)).thenReturn(context);
        when(context.newPage()).thenReturn(page);
        CountDownLatch firstCaptureStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCapture = new CountDownLatch(1);
        when(page.waitForResponse(
                org.mockito.ArgumentMatchers.<java.util.function.Predicate<Response>>any(),
                org.mockito.ArgumentMatchers.any(Page.WaitForResponseOptions.class),
                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    firstCaptureStarted.countDown();
                    if (!releaseFirstCapture.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test capture was not released");
                    }
                    return response;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.fetch(7L, "12345678", "sid=abc"));
            assertTrue(firstCaptureStarted.await(3, TimeUnit.SECONDS));

            PlatformItemDetailFetchService.FetchResult busy =
                    service.fetch(7L, "12345678", "sid=abc");
            assertEquals(PlatformItemDetailFetchService.Status.BUSY, busy.status());

            releaseFirstCapture.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS).isSuccess());
            assertTrue(service.fetch(7L, "12345678", "sid=abc").isSuccess());
            verify(playwrightManager, times(2)).createContext(7L);
        } finally {
            releaseFirstCapture.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void differentAccountsCanRunFallbackInParallel() throws Exception {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", true);
        when(apiCallUtils.callApiWithRetry(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(PlatformItemDetailFetchService.DETAIL_API),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "网络波动", false));
        BrowserContext context7 = mock(BrowserContext.class);
        BrowserContext context8 = mock(BrowserContext.class);
        Page page7 = mock(Page.class);
        Page page8 = mock(Page.class);
        when(playwrightManager.createContext(7L)).thenReturn(context7);
        when(playwrightManager.createContext(8L)).thenReturn(context8);
        when(context7.newPage()).thenReturn(page7);
        when(context8.newPage()).thenReturn(page8);
        Response response7 = detailResponse("700", response("700", "SUCCESS::调用成功"));
        Response response8 = detailResponse("800", response("800", "SUCCESS::调用成功"));
        CountDownLatch account7Started = new CountDownLatch(1);
        CountDownLatch releaseAccount7 = new CountDownLatch(1);
        when(page7.waitForResponse(
                org.mockito.ArgumentMatchers.<java.util.function.Predicate<Response>>any(),
                org.mockito.ArgumentMatchers.any(Page.WaitForResponseOptions.class),
                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    account7Started.countDown();
                    if (!releaseAccount7.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test capture was not released");
                    }
                    return response7;
                });
        when(page8.waitForResponse(
                org.mockito.ArgumentMatchers.<java.util.function.Predicate<Response>>any(),
                org.mockito.ArgumentMatchers.any(Page.WaitForResponseOptions.class),
                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return response8;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var account7 = executor.submit(() -> service.fetch(7L, "700", "sid=seven"));
            assertTrue(account7Started.await(3, TimeUnit.SECONDS));

            assertTrue(service.fetch(8L, "800", "sid=eight").isSuccess());

            releaseAccount7.countDown();
            assertTrue(account7.get(3, TimeUnit.SECONDS).isSuccess());
        } finally {
            releaseAccount7.countDown();
            executor.shutdownNow();
        }
    }

    private Response detailResponse(String itemId, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(
                "https://h5api.m.goofish.com/h5/mtop.taobao.idle.pc.detail/1.0/"
                        + "?data=%7B%22itemId%22%3A%22" + itemId + "%22%7D");
        when(response.ok()).thenReturn(true);
        when(response.text()).thenReturn(body);
        return response;
    }

    private String response(String itemId, String ret) {
        return "{\"ret\":[\"" + ret + "\"],\"data\":{\"itemDO\":{\"itemId\":\""
                + itemId + "\",\"desc\":\"详情\",\"skuList\":[]}}}";
    }
}
