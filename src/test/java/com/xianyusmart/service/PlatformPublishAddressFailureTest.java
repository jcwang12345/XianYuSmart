package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformPublishAddressFailureTest {
    @Test
    void publishBuilderRejectsOutOfRangeStockInsteadOfSilentlyClamping() {
        var service = service(mock(XianyuApiCallUtils.class));
        var images = List.of("https://img.alicdn.com/synthetic.png");
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validatePublishInput",
                "原创联调文本", "交付说明", images, new BigDecimal("0.01"), 9999));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "validatePublishInput",
                        "原创联调文本", "交付说明", images, new BigDecimal("0.01"), 10000));
        assertTrue(failure.getMessage().contains("1至9999"));
    }

    @Test
    void publishBuilderKeepsTitleSeparateFromDescription() {
        var service = service(mock(XianyuApiCallUtils.class));
        Map<String, Object> payload = ReflectionTestUtils.invokeMethod(service, "buildPublishData",
                "原创联调标题", "这是与标题不同的较长商品详情", new BigDecimal("0.01"), 1,
                List.of("https://img.alicdn.com/synthetic.png"), Map.of("catId", "50023914"),
                Map.of("prov", "河北省", "city", "石家庄市", "area", "长安区", "divisionId", 130102L),
                Map.of("deliveryMethod", "线上交付"));
        assertNotNull(payload);
        assertEquals(Map.of("title", "原创联调标题", "desc", "这是与标题不同的较长商品详情",
                "titleDescSeparate", true), payload.get("itemTextDTO"));
    }

    @Test
    void mixedSpecifiedFieldsCannotUseBasicBuilderEvenThroughOlderEntryPoint() {
        var api = mock(XianyuApiCallUtils.class);
        var service = service(api);
        for (Map<String, Object> fields : List.of(Map.<String, Object>of("leafCategoryCode", "LOCAL_ONLY"),
                Map.<String, Object>of("categoryAttributes", Map.of("deliveryFormat", "文档")))) {
            var request = new java.util.HashMap<String, Object>(Map.of("categoryMode", "BASIC_PLATFORM_RECOMMENDED", "publishChannel", "QR_COOKIE"));
            request.putAll(fields);
            assertEquals(409, assertThrows(BusinessException.class, () -> service.preflight(request, 9L)).getCode());
        }
        verifyNoInteractions(api);
    }

    @Test
    void basicRecommendationChangeRejectsActualPublishBeforeFinalWrite() throws Exception {
        var api = mock(XianyuApiCallUtils.class);
        var accounts = mock(AccountService.class);
        var uploads = mock(ImageUploadService.class);
        var policy = mock(PlatformWritePolicy.class);
        when(policy.enabled()).thenReturn(true);
        when(accounts.getCookieByAccountId(9L)).thenReturn("synthetic-cookie");
        when(api.callApiWithRetry(eq(9L), eq("mtop.taobao.idle.kgraph.property.recommend"), eq("2.0"),
                anyMap(), eq("synthetic-cookie"), isNull(), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"data\":{\"catId\":\"50023915\",\"catName\":\"changed\"}}", null, false));
        var service = new PlatformPublishService(mock(PlaywrightManager.class), accounts, new ObjectMapper(),
                api, mock(RiskControlService.class), uploads, mock(GoodsInfoService.class), policy);
        var material = new MerchantResource();
        material.setName("原创联调文本"); material.setAmount(new BigDecimal("0.01")); material.setStock(2);
        material.setDataJson(new ObjectMapper().writeValueAsString(Map.of("title", "原创联调文本",
                "description", "说明文本与七天核对指导", "images", List.of("https://img.alicdn.com/synthetic.png"),
                "categoryMode", "BASIC_PLATFORM_RECOMMENDED", "publishChannel", "QR_COOKIE",
                "confirmedPlatformCategoryId", "50023914", "divisionId", "130102")));

        var failure = assertThrows(BusinessException.class, () -> service.publish(material, 9L));
        assertEquals(409, failure.getCode());
        assertTrue(failure.getMessage().contains("类目已变化"));
        verify(api, never()).callApiWithRetry(anyLong(), eq("mtop.idle.pc.idleitem.publish"), anyMap(), anyString());
        verifyNoInteractions(uploads);
    }

    @Test
    void missingBasicConfirmationRejectsActualPublishBeforeAnyPlatformCall() {
        var api = mock(XianyuApiCallUtils.class);
        var accounts = mock(AccountService.class);
        var policy = mock(PlatformWritePolicy.class);
        when(policy.enabled()).thenReturn(true);
        when(accounts.getCookieByAccountId(9L)).thenReturn("synthetic-cookie");
        var service = new PlatformPublishService(mock(PlaywrightManager.class), accounts, new ObjectMapper(),
                api, mock(RiskControlService.class), mock(ImageUploadService.class), mock(GoodsInfoService.class), policy);
        var material = new MerchantResource(); material.setDataJson("{\"categoryMode\":\"BASIC_PLATFORM_RECOMMENDED\",\"publishChannel\":\"QR_COOKIE\"}");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.publish(material, 9L)).getCode());
        verifyNoInteractions(api);
    }

    @Test
    void recommendationComparisonAcceptsConfirmedIdButRejectsMissingAndNonQrExecution() {
        var service = service(mock(XianyuApiCallUtils.class));
        var request = new java.util.HashMap<String, Object>(Map.of("categoryMode", "BASIC_PLATFORM_RECOMMENDED", "publishChannel", "QR_COOKIE"));
        var category = Map.<String, Object>of("catId", "50023914");
        assertFalse(service.confirmRecommendedCategory(request, category, false));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.confirmRecommendedCategory(request, category, true)).getCode());
        request.put("confirmedPlatformCategoryId", "50023914");
        assertTrue(service.confirmRecommendedCategory(request, category, true));
        request.put("publishChannel", "OFFICIAL_OAUTH");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.confirmRecommendedCategory(request, category, true)).getCode());
    }

    @Test
    void successfulPlatformReadWithoutDefaultAddressReturnsActionable400() {
        var api = mock(XianyuApiCallUtils.class);
        var service = service(api);
        when(api.callApiWithRetry(9L, "mtop.idle.pc.idleitem.preget", Map.of(), "synthetic-cookie"))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, "{\"data\":{}}", null, false));

        var failure = assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "resolveAddress", 9L,
                        "synthetic-cookie", Map.of(), Map.of()));

        assertEquals(400, failure.getCode());
        assertTrue(failure.getMessage().contains("请填写省、市、区"));
        assertFalse(failure.getMessage().contains("synthetic-cookie"));
        verify(api).callApiWithRetry(9L, "mtop.idle.pc.idleitem.preget", Map.of(), "synthetic-cookie");
        verifyNoMoreInteractions(api);
    }

    @Test
    void explicitDivisionAvoidsMissingDefaultAddressLookup() {
        var api = mock(XianyuApiCallUtils.class);
        Map<String, Object> address = ReflectionTestUtils.invokeMethod(service(api), "resolveAddress",
                9L, "synthetic-cookie", Map.of("divisionId", "130102", "district", "长安区"), Map.of());
        assertNotNull(address);
        assertEquals(130102L, address.get("divisionId"));
        verifyNoInteractions(api);
    }

    private PlatformPublishService service(XianyuApiCallUtils api) {
        return new PlatformPublishService(mock(PlaywrightManager.class), mock(AccountService.class),
                new ObjectMapper(), api, mock(RiskControlService.class), mock(ImageUploadService.class),
                mock(GoodsInfoService.class), mock(PlatformWritePolicy.class));
    }
}
