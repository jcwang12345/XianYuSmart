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
    void disabledFallbackReturnsRedactedApiFailure() {
        ReflectionTestUtils.setField(service, "browserFallbackEnabled", false);
        when(apiCallUtils.callApiWithRetry(7L, PlatformItemDetailFetchService.DETAIL_API,
                java.util.Map.of("itemId", "12345678"), "sid=abc"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, null, "token=secret cookie=private", false));

        PlatformItemDetailFetchService.FetchResult result =
                service.fetch(7L, "12345678", "sid=abc");

        assertEquals(PlatformItemDetailFetchService.Status.UNAVAILABLE, result.status());
        assertFalse(result.errorMessage().contains("secret"));
        assertFalse(result.errorMessage().contains("private"));
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
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiFailureUsesReadOnlyAccountIsolatedPageCapture() {
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
