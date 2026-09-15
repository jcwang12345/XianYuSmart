package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.config.PlaywrightManager;
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
    private PlatformPublishService service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        apiCallUtils = mock(XianyuApiCallUtils.class);
        riskControlService = mock(RiskControlService.class);
        writePolicy = mock(PlatformWritePolicy.class);
        service = new PlatformPublishService(
                mock(PlaywrightManager.class), accountService, new ObjectMapper(), apiCallUtils,
                riskControlService, mock(ImageUploadService.class), mock(GoodsInfoService.class), writePolicy);
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
