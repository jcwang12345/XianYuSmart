package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformPublishServiceEditTest {

    private AccountService accountService;
    private XianyuApiCallUtils apiCallUtils;
    private RiskControlService riskControlService;
    private PlatformWritePolicy writePolicy;
    private GoodsInfoService goodsInfoService;
    private PlatformPublishService service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        apiCallUtils = mock(XianyuApiCallUtils.class);
        riskControlService = mock(RiskControlService.class);
        writePolicy = mock(PlatformWritePolicy.class);
        goodsInfoService = mock(GoodsInfoService.class);
        service = new PlatformPublishService(
                mock(PlaywrightManager.class), accountService, new ObjectMapper(), apiCallUtils,
                riskControlService, mock(ImageUploadService.class), goodsInfoService, writePolicy);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pricePayloadPreservesPlatformSnapshotAndUpdatesSimpleSkuInCents() {
        Map<String, Object> snapshot = snapshot();

        Map<String, Object> payload = service.buildEditPayload(
                snapshot, "12345678", new BigDecimal("12.34"), null);

        assertEquals("1234", ((Map<String, Object>) payload.get("itemPriceDTO")).get("priceInCent"));
        assertEquals("1234", ((Map<String, Object>) ((List<?>) payload.get("itemSkuList")).getFirst())
                .get("priceInCent"));
        assertEquals("旧标题", ((Map<String, Object>) payload.get("itemTextDTO")).get("title"));
        assertEquals("pcMainPublish", payload.get("publishScene"));
        assertEquals("12345678", payload.get("sourceId"));
        assertFalse(payload.containsKey("unknownPlatformInternalField"));
        assertEquals("999", ((Map<String, Object>) snapshot.get("itemPriceDTO")).get("priceInCent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockPayloadUpdatesRootAndSimpleSkuWithoutChangingPrice() {
        Map<String, Object> payload = service.buildEditPayload(snapshot(), "12345678", null, 8);

        assertEquals("8", payload.get("quantity"));
        Map<String, Object> sku = (Map<String, Object>) ((List<?>) payload.get("itemSkuList")).getFirst();
        assertEquals("8", sku.get("quantity"));
        assertEquals("999", sku.get("priceInCent"));
    }

    @Test
    void editBuilderRejectsMultiSkuAndInvalidPriceInsteadOfFlatteningVariants() {
        Map<String, Object> snapshot = snapshot();
        snapshot.put("itemSkuList", List.of(
                Map.of("priceInCent", "999", "quantity", "2", "propertyList", List.of(Map.of("value", "A"))),
                Map.of("priceInCent", "1099", "quantity", "3", "propertyList", List.of(Map.of("value", "B")))));

        IllegalStateException multiSku = assertThrows(IllegalStateException.class,
                () -> service.buildEditPayload(snapshot, "12345678", new BigDecimal("12.34"), null));
        assertTrue(multiSku.getMessage().contains("逐 SKU"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildEditPayload(snapshot(), "12345678", new BigDecimal("1.234"), null));
    }

    @Test
    void platformEditRequiresSuccessfulReadBackBeforeReportingSuccess() {
        when(writePolicy.enabled()).thenReturn(true);
        when(accountService.getCookieByAccountId(1L)).thenReturn("cookie=ok");
        when(riskControlService.tryAcquire(1L, RiskControlService.WriteOperation.ITEM_EDIT))
                .thenReturn(new RiskControlService.GuardDecision(true, RiskControlService.GuardState.NORMAL,
                        0, 0, null, RiskControlService.WriteOperation.ITEM_EDIT));
        XianyuApiCallUtils.ApiCallResult before = successResponse("999", "2");
        XianyuApiCallUtils.ApiCallResult after = successResponse("1234", "2");
        when(apiCallUtils.callApiWithRetry(eq(1L), eq("mtop.idle.pc.idleitem.editDetail"), eq("1.0"),
                any(), eq("cookie=ok"), isNull(), isNull())).thenReturn(before, after);
        when(apiCallUtils.callApiWithRetry(eq(1L), eq("mtop.idle.pc.idleitem.edit"), eq("1.0"),
                any(), eq("cookie=ok"), isNull(), isNull()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}", null, false));

        Map<String, Object> result = service.editPriceOrStock(
                1L, "12345678", new BigDecimal("12.34"), null);

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("platformReadBackVerified"));
        verify(riskControlService).tryAcquire(1L, RiskControlService.WriteOperation.ITEM_EDIT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedFieldVerificationDistinguishesExactPlatformNormalizationAndMissingEvidence() {
        Map<String, Object> requested = snapshot();
        Map<String, Object> exact = snapshot();

        Map<String, Object> same = service.verifyPublishedFields(requested, exact);
        assertEquals("SAME", same.get("status"));
        assertEquals(true, same.get("verificationComplete"));
        assertEquals(0, same.get("changedFieldCount"));

        Map<String, Object> normalized = snapshot();
        normalized.put("itemTextDTO", Map.of("title", "平台规范标题", "desc", "旧详情"));
        normalized.put("imageInfoDOList", List.of(
                Map.of("url", "https://img.alicdn.com/a.jpg?platform-process=resize")));
        Map<String, Object> different = service.verifyPublishedFields(requested, normalized);
        assertEquals("DIFFERENT", different.get("status"));
        assertEquals(true, different.get("verificationComplete"));
        assertEquals(1, different.get("changedFieldCount"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) different.get("items");
        assertEquals("DIFFERENT", items.stream().filter(item -> "title".equals(item.get("field")))
                .findFirst().orElseThrow().get("status"));
        assertEquals("SAME", items.stream().filter(item -> "images".equals(item.get("field")))
                .findFirst().orElseThrow().get("status"));

        Map<String, Object> partialSnapshot = snapshot();
        partialSnapshot.remove("itemCatDTO");
        Map<String, Object> partial = service.verifyPublishedFields(requested, partialSnapshot);
        assertEquals("PARTIAL", partial.get("status"));
        assertEquals(false, partial.get("verificationComplete"));
        assertEquals(1, partial.get("unavailableFieldCount"));
    }

    @Test
    void publishSeparatesConfirmedCreationFromFieldReadBackAndPersistsActualPlatformValues() throws Exception {
        when(writePolicy.enabled()).thenReturn(true);
        when(accountService.getCookieByAccountId(1L)).thenReturn("cookie=ok");
        when(riskControlService.tryAcquire(1L, RiskControlService.WriteOperation.ITEM_PUBLISH))
                .thenReturn(new RiskControlService.GuardDecision(true, RiskControlService.GuardState.NORMAL,
                        0, 0, null, RiskControlService.WriteOperation.ITEM_PUBLISH));
        when(goodsInfoService.savePublishedGoods(anyString(), eq(1L), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(true);
        when(apiCallUtils.callApiWithRetry(eq(1L), eq("mtop.taobao.idle.kgraph.property.recommend"),
                eq("2.0"), any(), eq("cookie=ok"), isNull(), any()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"data\":{\"categoryPredictResult\":{\"catId\":\"5001\",\"catName\":\"软件\"}}}",
                        null, false));
        when(apiCallUtils.callApiWithRetry(eq(1L), eq("mtop.idle.pc.idleitem.publish"),
                any(), eq("cookie=ok")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"data\":{\"itemId\":\"12345678\"}}", null, false));
        when(apiCallUtils.callApiWithRetry(eq(1L), eq("mtop.idle.pc.idleitem.editDetail"), eq("1.0"),
                any(), eq("cookie=ok"), isNull(), isNull()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true,
                        "{\"data\":{\"itemId\":\"12345678\",\"quantity\":\"2\","
                                + "\"itemPriceDTO\":{\"priceInCent\":\"1234\"},"
                                + "\"itemTextDTO\":{\"title\":\"平台规范标题\",\"desc\":\"发布详情\"},"
                                + "\"itemCatDTO\":{\"catId\":\"5001\",\"catName\":\"软件\"},"
                                + "\"imageInfoDOList\":[{\"url\":\"https://img.alicdn.com/a.jpg?x=1\"}]}}",
                        null, false));

        MerchantResource material = new MerchantResource();
        material.setName("提交标题");
        material.setAmount(new BigDecimal("12.34"));
        material.setStock(2);
        material.setDataJson(new ObjectMapper().writeValueAsString(Map.of(
                "title", "提交标题",
                "description", "发布详情",
                "images", List.of("https://img.alicdn.com/a.jpg"),
                "divisionId", "110101",
                "prov", "北京市",
                "city", "北京市",
                "area", "东城区")));

        Map<String, Object> result = service.publish(material, 1L);

        assertEquals("CONFIRMED", result.get("platformWrite"));
        assertEquals("VERIFIED", result.get("verificationStatus"));
        assertEquals(true, result.get("platformReadBackVerified"));
        assertEquals("DIFFERENT", ((Map<?, ?>) result.get("fieldDifferences")).get("status"));
        verify(goodsInfoService).savePublishedGoods(eq("12345678"), eq(1L), eq("平台规范标题"),
                eq("https://img.alicdn.com/a.jpg?x=1"), anyString(), eq("发布详情"),
                eq("https://www.goofish.com/item?id=12345678"), eq("12.34"));
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("itemId", "12345678");
        snapshot.put("quantity", "2");
        snapshot.put("itemPriceDTO", new LinkedHashMap<>(Map.of("priceInCent", "999", "origPriceInCent", "1599")));
        snapshot.put("itemTextDTO", Map.of("title", "旧标题", "desc", "旧详情"));
        snapshot.put("itemCatDTO", Map.of("catId", "5001"));
        snapshot.put("imageInfoDOList", List.of(Map.of("url", "https://img.alicdn.com/a.jpg")));
        snapshot.put("itemSkuList", List.of(new LinkedHashMap<>(Map.of(
                "priceInCent", "999", "quantity", "2", "propertyList", List.of()))));
        snapshot.put("unknownPlatformInternalField", "must-not-submit");
        return snapshot;
    }

    private XianyuApiCallUtils.ApiCallResult successResponse(String cents, String quantity) {
        String json = "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{" +
                "\"itemId\":\"12345678\",\"quantity\":\"" + quantity + "\"," +
                "\"itemPriceDTO\":{\"priceInCent\":\"" + cents + "\"}," +
                "\"itemTextDTO\":{\"title\":\"旧标题\",\"desc\":\"旧详情\"}," +
                "\"itemSkuList\":[{\"priceInCent\":\"" + cents + "\",\"quantity\":\"" + quantity + "\",\"propertyList\":[]}]}}";
        return new XianyuApiCallUtils.ApiCallResult(true, json, null, false);
    }
}
