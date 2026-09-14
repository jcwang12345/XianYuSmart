package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishQaMockServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refusesUnsafeConfigurationOutsideQaProfile() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        PublishQaMockService service = service(environment, mock(GoodsInfoService.class), true);

        assertThrows(IllegalStateException.class, service::validateSafetyBoundary);
    }

    @Test
    void preflightIsExplicitlyLocalAndNetworkFree() {
        PublishQaMockService service = qaService(mock(GoodsInfoService.class));
        Map<String, Object> result = service.preflight(Map.of(
                "name", "QA-PUBLISH-插件测试商品",
                "description", "仅隔离测试",
                "amount", new BigDecimal("19.90"),
                "stock", 3,
                "images", List.of("https://example.test/qa.jpg"),
                "publishChannel", "QA_LOCAL"), 1L, 101L);

        assertEquals("QA_MOCK", result.get("executionChannel"));
        assertEquals(false, result.get("platformNetworkCalls"));
        assertEquals(false, result.get("previewUsesProductionBuilder"));
    }

    @Test
    void executeSuccessPersistsQaProductWithoutPlatformWrite() throws Exception {
        GoodsInfoService goods = mock(GoodsInfoService.class);
        when(goods.savePublishedGoods(anyString(), eq(101L), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(true);
        PublishQaMockService service = qaService(goods);
        MerchantTask task = task(77L);
        MerchantResource material = material(Map.of(
                "name", "QA-PUBLISH-成功场景",
                "description", "QA",
                "publishChannel", "QA_LOCAL",
                "qaScenario", "SUCCESS",
                "images", List.of("https://example.test/qa.jpg")));

        Map<String, Object> result = service.execute(task, material, 1L, 101L);

        assertEquals("QA-PUBLISHED-77", result.get("itemId"));
        assertEquals("NOT_PERFORMED", result.get("platformWrite"));
        assertEquals(false, result.get("platformNetworkCalls"));
        verify(goods).savePublishedGoods(eq("QA-PUBLISHED-77"), eq(101L), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownNeverBecomesRetryableFailure() throws Exception {
        PublishQaMockService service = qaService(mock(GoodsInfoService.class));
        MerchantResource material = material(Map.of(
                "name", "QA-PUBLISH-未知场景",
                "publishChannel", "QA_LOCAL",
                "qaScenario", "UNKNOWN"));

        assertThrows(PublishQaMockService.QaPublishOutcomeUnknownException.class,
                () -> service.execute(task(88L), material, 1L, 101L));
    }

    @Test
    void tenantAccountChannelAndPrefixAreAllRequired() {
        PublishQaMockService service = qaService(mock(GoodsInfoService.class));

        assertTrue(service.isEligible(1L, 101L, "QA_LOCAL", "QA-PUBLISH-安全商品"));
        assertFalse(service.isEligible(2L, 101L, "QA_LOCAL", "QA-PUBLISH-安全商品"));
        assertFalse(service.isEligible(1L, 999L, "QA_LOCAL", "QA-PUBLISH-安全商品"));
        assertFalse(service.isEligible(1L, 101L, "QR_COOKIE", "QA-PUBLISH-安全商品"));
        assertThrows(BusinessException.class,
                () -> service.requireEligible(1L, 101L, "QA_LOCAL", "真实商品"));
    }

    private PublishQaMockService qaService(GoodsInfoService goods) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"qa"});
        PublishQaMockService service = service(environment, goods, true);
        service.validateSafetyBoundary();
        return service;
    }

    private PublishQaMockService service(Environment environment, GoodsInfoService goods, boolean enabled) {
        return new PublishQaMockService(environment, mapper, goods, enabled, 1L,
                "101,102,103", "QA-PUBLISH-");
    }

    private MerchantTask task(Long id) {
        MerchantTask task = new MerchantTask();
        task.setId(id);
        task.setTenantId(1L);
        task.setRequestKey("qa-publish-" + id);
        return task;
    }

    private MerchantResource material(Map<String, Object> data) throws Exception {
        MerchantResource material = new MerchantResource();
        material.setResourceType("MATERIAL");
        material.setName(String.valueOf(data.get("name")));
        material.setAmount(new BigDecimal("19.90"));
        material.setStock(1);
        material.setDataJson(mapper.writeValueAsString(data));
        return material;
    }
}
