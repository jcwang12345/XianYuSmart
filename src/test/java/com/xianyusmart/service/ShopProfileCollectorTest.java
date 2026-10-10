package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import com.xianyusmart.context.TenantContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopProfileCollectorTest {

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private static final String HEAD = """
            {"ret":["SUCCESS"],"data":{"baseInfo":{"kcUserId":"u1","userType":1,"tags":{}},
            "module":{"base":{"displayName":"API昵称","avatar":{"avatar":"https://img.alicdn.com/a.png"},
            "ipLocation":"上海","ylzTags":[]},"shop":{"level":"L1","score":1,"superShow":false,
            "itemToppingLimit":1,"reviewNum":2,"praiseRatio":100},"social":{"followers":3},
            "tabs":{"item":{"number":4}}}}}
            """;
    private static final String NAV = "{\"ret\":[\"SUCCESS\"],\"data\":{\"module\":{\"base\":{\"purchaseCount\":5,\"soldCount\":6}}}}";
    private static final String ITEMS = "{\"ret\":[\"SUCCESS\"],\"data\":{\"itemGroupList\":[]}}";

    @Test
    void completeApiResponsesNeverOpenBrowser() {
        XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
        PlaywrightManager browser = mock(PlaywrightManager.class);
        when(api.callApiWithRetry(eq(1L), anyString(), anyMap(), eq("c=v")))
                .thenReturn(ok(HEAD), ok(NAV), ok(ITEMS));
        ShopProfileCollector collector = new ShopProfileCollector(api, browser, new ObjectMapper());

        ShopProfileCollector.CollectionResult result = collector.collect(1L, "u1", "c=v");

        assertTrue(result.success());
        assertEquals("PLATFORM_API", result.source());
        assertFalse(result.browserFallbackUsed());
        verifyNoInteractions(browser);
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialApiUsesPageEvidenceAndParserKeepsVerifiedApiFields() {
        XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
        PlaywrightManager manager = mock(PlaywrightManager.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator body = mock(Locator.class);
        when(api.callApiWithRetry(eq(1L), anyString(), anyMap(), eq("c=v")))
                .thenReturn(ok(HEAD.replace("\"ipLocation\":\"上海\",", "")), ok(NAV), ok(ITEMS));
        when(manager.createContext(1L)).thenReturn(context);
        when(context.newPage()).thenReturn(page);
        when(page.locator("body")).thenReturn(body);
        when(body.innerText()).thenReturn("");
        when(page.url()).thenReturn("https://www.goofish.com/personal?userId=u1");
        final Consumer<Response>[] listener = new Consumer[1];
        doAnswer(call -> { listener[0] = call.getArgument(0); return null; }).when(page).onResponse(any());
        String pageHead = HEAD.replace("API昵称", "页面昵称").replace("上海", "杭州");
        doAnswer(call -> {
            listener[0].accept(response(ShopProfileCollector.HEAD_API, pageHead, "u1"));
            listener[0].accept(response(ShopProfileCollector.NAV_API, NAV, "u1"));
            listener[0].accept(response(ShopProfileCollector.ITEM_API, ITEMS, "u1"));
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        ShopProfileCollector collector = new ShopProfileCollector(api, manager, new ObjectMapper());

        ShopProfileCollector.CollectionResult result = collector.collect(1L, "u1", "c=v");
        ShopProfileParser.ParsedProfile parsed = new ShopProfileParser(new ObjectMapper()).parseMerged("u1", result);

        assertEquals("MIXED", result.source());
        assertEquals("API昵称", parsed.shopNickname());
        assertEquals("杭州", parsed.region());
        assertEquals("PLATFORM_API", parsed.fieldEvidence().get("shopNickname").source());
        assertEquals("PLATFORM_WEB", parsed.fieldEvidence().get("region").source());
    }

    @Test
    void rejectsWrongIdentityForEveryCapturedResponseType() {
        ShopProfileCollector collector = new ShopProfileCollector(mock(XianyuApiCallUtils.class),
                mock(PlaywrightManager.class), new ObjectMapper());
        for (String api : new String[]{ShopProfileCollector.HEAD_API, ShopProfileCollector.NAV_API,
                ShopProfileCollector.ITEM_API}) {
            Map<String, String> responses = new LinkedHashMap<>();
            collector.capture(response(api, HEAD, "other"), "u1", responses);
            assertTrue(responses.isEmpty(), api + " must be filtered by request identity");
        }
        Map<String, String> responses = new LinkedHashMap<>();
        Response wrongDomain = mock(Response.class);
        when(wrongDomain.url()).thenReturn("https://evil.example/h5/" + ShopProfileCollector.HEAD_API + "/1.0/?data=%7B%22userId%22%3A%22u1%22%7D");
        when(wrongDomain.ok()).thenReturn(true);
        collector.capture(wrongDomain, "u1", responses);
        Response wrongApi = mock(Response.class);
        when(wrongApi.url()).thenReturn("https://h5api.m.goofish.com/h5/mtop.other/1.0/?data=%7B%22userId%22%3A%22u1%22%7D");
        when(wrongApi.ok()).thenReturn(true);
        collector.capture(wrongApi, "u1", responses);
        Response failed = response(ShopProfileCollector.HEAD_API, HEAD, "u1");
        when(failed.ok()).thenReturn(false);
        collector.capture(failed, "u1", responses);
        collector.capture(response(ShopProfileCollector.HEAD_API,
                "{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED\"],\"data\":{}}", "u1"), "u1", responses);
        assertTrue(responses.isEmpty());
    }

    @Test
    void dailyPageFallbackCapStopsBeforeOpeningBrowser() {
        XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
        PlaywrightManager browser = mock(PlaywrightManager.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(api.callApiWithRetry(eq(1L), anyString(), anyMap(), eq("c=v")))
                .thenReturn(ok(HEAD.replace("\"ipLocation\":\"上海\",", "")), ok(NAV), ok(ITEMS));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        TenantContext.set(8L);

        ShopProfileCollector.CollectionResult result = new ShopProfileCollector(api, browser,
                new ObjectMapper(), jdbc).collect(1L, "u1", "c=v");

        assertFalse(result.success());
        assertEquals("RATE_LIMITED", result.errorCode());
        verifyNoInteractions(browser);
    }

    private static XianyuApiCallUtils.ApiCallResult ok(String json) {
        return new XianyuApiCallUtils.ApiCallResult(true, json, null, false);
    }

    private static Response response(String api, String body, String userId) {
        Response response = mock(Response.class);
        String data = URLEncoder.encode("{\"userId\":\"" + userId + "\"}", StandardCharsets.UTF_8);
        when(response.url()).thenReturn("https://h5api.m.goofish.com/h5/" + api + "/1.0/?data=" + data);
        when(response.ok()).thenReturn(true);
        when(response.text()).thenReturn(body);
        return response;
    }
}
