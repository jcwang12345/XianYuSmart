package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingDraftServiceTest {

    @Test
    void qrBasicLocalSupportMetadataIsOptionalButProvidedValidityMustBeValid() {
        Map<String, Object> request = basicVirtual();
        request.remove("supportPolicy"); request.remove("validityDays"); request.remove("fulfillmentMode");
        assertTrue(ListingDraftService.validatePayload(request).errors().isEmpty());
        request.put("validityDays", 0);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .anyMatch(field -> "validityDays".equals(field.get("field"))));
    }

    @Test
    void qrBasicStockMatchesRealPublishBuilderLimitWithoutChangingOtherDraftModes() {
        Map<String, Object> request = basicVirtual();
        request.put("stock", 9999);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .noneMatch(field -> "stock".equals(field.get("field"))));
        request.put("stock", 10000);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .anyMatch(field -> "stock".equals(field.get("field"))));
        request.put("stock", 1.5);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .anyMatch(field -> "stock".equals(field.get("field"))));
        request = validVirtual();
        request.put("stock", 10000);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .noneMatch(field -> "stock".equals(field.get("field"))));
    }

    @Test
    void explicitQrBasicModeValidatesWithoutLocalCategoryButNeedsConfirmationForExecute() {
        Map<String, Object> request = basicVirtual();
        ListingDraftService service = basicService(mock(JdbcTemplate.class));
        assertTrue((Boolean) service.requirePreflightEligible(request).get("valid"));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.requireExecutable(request)).getCode());
        request.put("confirmedPlatformCategoryId", "50023914");
        assertTrue((Boolean) service.requireExecutable(request).get("valid"));
    }

    @Test
    void basicModeRejectsSpecifiedFieldsAndRetainsAdvancedGuards() {
        for (Map<String, Object> fields : List.of(Map.<String, Object>of("leafCategoryCode", "OFFICE_PLUGIN"),
                Map.<String, Object>of("categoryAttributes", Map.of("deliveryFormat", "文档")),
                Map.<String, Object>of("categoryAttributes", List.of("invalid")))) {
            Map<String, Object> request = basicVirtual(); request.putAll(fields);
            assertFalse(ListingDraftService.validatePayload(request).errors().isEmpty());
        }
        Map<String, Object> request = basicVirtual(); request.put("videoUrl", "https://example.test/movie.mp4");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> basicService(mock(JdbcTemplate.class)).requirePreflightEligible(request)).getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"QA_LOCAL", "OFFICIAL_OAUTH", "UNKNOWN_CHANNEL"})
    void basicModeCannotExpandOtherChannels(String channel) {
        Map<String, Object> request = basicVirtual(); request.put("publishChannel", channel);
        assertTrue(ListingDraftService.validatePayload(request).fieldErrors().stream()
                .anyMatch(field -> "categoryMode".equals(field.get("field"))));
    }

    @Test
    void unknownModeIsRejectedAndOldDraftIsNotMigrated() {
        Map<String, Object> basic = basicVirtual(); basic.put("categoryMode", "basic_platform_recommended");
        assertFalse(ListingDraftService.validatePayload(basic).errors().isEmpty());
        Map<String, Object> legacy = validVirtual(); legacy.put("xianyuAccountId", 9L);
        legacy.put("publishChannel", "QR_COOKIE"); legacy.put("businessMode", "NORMAL");
        BusinessException refusal = assertThrows(BusinessException.class,
                () -> basicService(mock(JdbcTemplate.class)).requirePreflightEligible(legacy));
        assertEquals(409, refusal.getCode());
        assertTrue(refusal.getMessage().contains("指定叶子类目"));
        assertEquals("OFFICE_PLUGIN", legacy.get("leafCategoryCode"));
    }

    @Test
    void recommendationMustBeConfirmedAndStillMatch() {
        ListingDraftService service = basicService(mock(JdbcTemplate.class));
        Map<String, Object> request = basicVirtual();
        Map<String, Object> result = Map.of("platform", Map.of("category", Map.of("catId", "50023914")));
        assertFalse(service.preflightCategoryConfirmed(request, result));
        request.put("confirmedPlatformCategoryId", "50023914");
        assertTrue(service.preflightCategoryConfirmed(request, result));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.preflightCategoryConfirmed(request,
                        Map.of("platform", Map.of("category", Map.of("catId", "50023915"))))).getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"categoryMode", "confirmedPlatformCategoryId"})
    void changedModeOrConfirmedCategoryInvalidatesPreflightFingerprint(String field) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ListingDraftService service = basicService(jdbc);
        Map<String, Object> request = basicVirtual();
        request.put("requestId", "basic-fingerprint-test"); request.put("previewToken", "synthetic-preview");
        request.put("confirmedPlatformCategoryId", "50023914");
        String fingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", request);
        when(jdbc.queryForList(contains("preview_token=?"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1L, "xianyu_account_id", 9L,
                        "request_id", "basic-fingerprint-test", "payload_fingerprint", fingerprint,
                        "expires_time", Timestamp.from(Instant.now().plusSeconds(600)))));
        request.put(field, field.equals("categoryMode") ? "SPECIFIED_CATALOG" : "50023915");
        TenantContext.set(1L);
        try {
            assertEquals(409, assertThrows(BusinessException.class, () -> service.consumePreflight(request)).getCode());
            verify(jdbc, never()).update(contains("status='CONSUMED'"), any(Object[].class));
        } finally { TenantContext.clear(); }
    }

    private Map<String, Object> basicVirtual() {
        Map<String, Object> request = validVirtual();
        request.remove("industryCode"); request.remove("leafCategoryCode");
        request.put("xianyuAccountId", 9L); request.put("publishChannel", "QR_COOKIE");
        request.put("categoryMode", "BASIC_PLATFORM_RECOMMENDED"); request.put("businessMode", "NORMAL");
        return request;
    }

    private ListingDraftService basicService(JdbcTemplate jdbc) {
        ListingCatalogService catalog = mock(ListingCatalogService.class);
        when(catalog.active("VIRTUAL")).thenReturn(new ListingCatalogService.Catalog("catalog-v1",
                "LOCAL_REFERENCE", "REFERENCE_ONLY", "VIRTUAL",
                List.of(Map.of("code", "SOFTWARE", "leafCategories", List.of(Map.of("code", "OFFICE_PLUGIN")))),
                Map.of("OFFICE_PLUGIN", Map.of("code", "OFFICE_PLUGIN", "attributes", List.of()))));
        PublishCapabilityService capabilities = mock(PublishCapabilityService.class);
        when(capabilities.capabilities(9L)).thenReturn(Map.of("channels",
                List.of(Map.of("channelCode", "QR_COOKIE", "features", Map.of()))));
        ProductContentPolicyService content = mock(ProductContentPolicyService.class);
        when(content.inspect(anyMap())).thenReturn(Map.of("valid", true));
        return new ListingDraftService(jdbc, new ObjectMapper(), mock(AccountAccessService.class),
                capabilities, catalog, mock(OperationLogService.class), content);
    }

    @Test
    void acceptsCompleteVirtualListingAndKeepsAdvancedFieldsAsWarnings() {
        Map<String, Object> payload = validVirtual();
        payload.put("skuDimensions", List.of(Map.of("name", "平台", "values", List.of("Office"))));
        payload.put("skus", List.of(Map.of("key", "Office", "values", Map.of("平台", "Office"), "price", 9.9, "stock", 10)));
        payload.put("videoUrl", "https://example.test/demo.mp4");

        ListingDraftService.Validation result = ListingDraftService.validatePayload(payload);

        assertTrue(result.errors().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("多SKU")));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("视频")));
    }

    @Test
    void rejectsInvalidPricePrecisionAndOriginalPrice() {
        Map<String, Object> payload = validVirtual();
        payload.put("amount", "1.234");
        payload.put("originalPrice", "1.00");

        ListingDraftService.Validation result = ListingDraftService.validatePayload(payload);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("两位小数")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("原价")));
    }

    @Test
    void acceptsTwoDecimalPriceAndUpperBoundButRejectsThreeDecimals() {
        for (String accepted : List.of("0.01", "12.34", "99999999.99")) {
            Map<String, Object> payload = validVirtual();
            payload.put("amount", accepted);
            assertTrue(ListingDraftService.validatePayload(payload).errors().stream()
                    .noneMatch(value -> value.contains("售价必须")), accepted);
        }
        for (String rejected : List.of("0.001", "1.234", "100000000.00")) {
            Map<String, Object> payload = validVirtual();
            payload.put("amount", rejected);
            assertTrue(ListingDraftService.validatePayload(payload).errors().stream()
                    .anyMatch(value -> value.contains("售价必须")), rejected);
        }
    }

    @Test
    void rejectsDuplicateMediaAndInvalidSkuRowsWithFieldPaths() {
        Map<String, Object> payload = validVirtual();
        payload.put("images", List.of("https://example.test/item.jpg", "https://example.test/item.jpg"));
        payload.put("skuDimensions", List.of(Map.of("name", "版本", "values", List.of("标准", "专业"))));
        payload.put("skus", List.of(
                Map.of("key", "标准", "price", "1.234", "stock", 1, "merchantCode", "SAME"),
                Map.of("key", "专业", "price", "2.00", "stock", -1, "merchantCode", "SAME")));

        ListingDraftService.Validation result = ListingDraftService.validatePayload(payload);

        assertTrue(result.fieldErrors().stream().anyMatch(item -> "images[1]".equals(item.get("field"))));
        assertTrue(result.fieldErrors().stream().anyMatch(item -> "skus[0].price".equals(item.get("field"))));
        assertTrue(result.fieldErrors().stream().anyMatch(item -> "skus[1].stock".equals(item.get("field"))));
        assertTrue(result.fieldErrors().stream().anyMatch(item -> "skus[1].merchantCode".equals(item.get("field"))));
    }

    @Test
    void enforcesDistinctVirtualPhysicalAndServiceFulfillment() {
        Map<String, Object> service = validVirtual();
        service.put("productType", "SERVICE");
        service.put("shippingMode", "ONLINE_DELIVERY");
        service.put("serviceDurationMinutes", 0);
        service.put("appointmentLeadHours", -1);
        assertTrue(ListingDraftService.validatePayload(service).fieldErrors().stream()
                .anyMatch(item -> "shippingMode".equals(item.get("field"))));

        Map<String, Object> physical = validVirtual();
        physical.put("productType", "PHYSICAL");
        physical.put("shippingMode", "FREE_SHIPPING");
        physical.remove("afterSalesPolicy");
        assertTrue(ListingDraftService.validatePayload(physical).fieldErrors().stream()
                .anyMatch(item -> "afterSalesPolicy".equals(item.get("field"))));
    }

    @Test
    void rejectsShippingMismatchAndMissingFreightTemplate() {
        Map<String, Object> payload = validVirtual();
        payload.put("productType", "PHYSICAL");
        payload.put("shippingMode", "ONLINE_DELIVERY");
        assertTrue(ListingDraftService.validatePayload(payload).errors().stream()
                .anyMatch(value -> value.contains("实物商品")));

        payload.put("shippingMode", "FREIGHT_TEMPLATE");
        assertTrue(ListingDraftService.validatePayload(payload).errors().stream()
                .anyMatch(value -> value.contains("模板ID")));
    }

    @Test
    void rejectsMoreThanTwoDimensionsOrFiftySkuCombinations() {
        Map<String, Object> payload = validVirtual();
        payload.put("skuDimensions", List.of(Map.of("name", "平台"), Map.of("name", "期限"), Map.of("name", "版本")));
        List<Map<String, Object>> skus = new ArrayList<>();
        for (int i = 0; i < 51; i++) skus.add(Map.of("key", String.valueOf(i)));
        payload.put("skus", skus);

        ListingDraftService.Validation result = ListingDraftService.validatePayload(payload);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("2个规格维度")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("50个")));
    }

    @Test
    void reportsMissingRequiredContentWithoutInventingDefaults() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productType", "VIRTUAL");
        payload.put("shippingMode", "ONLINE_DELIVERY");

        ListingDraftService.Validation result = ListingDraftService.validatePayload(payload);

        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("标题")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("图片")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationMergesContentPolicyFindingsIntoFieldErrors() {
        ListingCatalogService catalogService = mock(ListingCatalogService.class);
        when(catalogService.active("VIRTUAL")).thenReturn(new ListingCatalogService.Catalog(
                "catalog-v1", "LOCAL_REFERENCE", "REFERENCE_ONLY", "VIRTUAL", List.of(), Map.of()));
        ProductContentPolicyService contentPolicy = mock(ProductContentPolicyService.class);
        Map<String, Object> finding = Map.of(
                "field", "description", "fieldLabel", "商品详情", "code", "PROHIBITED_TERM",
                "severity", "BLOCKER", "term", "站外交易", "message", "商品详情命中风险词“站外交易”");
        when(contentPolicy.inspect(anyMap())).thenReturn(Map.of(
                "valid", false, "findings", List.of(finding), "summary", "发布内容命中 1 项阻断风险"));
        ListingDraftService service = new ListingDraftService(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(AccountAccessService.class),
                mock(PublishCapabilityService.class), catalogService, mock(OperationLogService.class), contentPolicy);
        Map<String, Object> payload = validVirtual();
        payload.put("xianyuAccountId", 101L);

        Map<String, Object> result = service.validate(payload);

        assertFalse((Boolean) result.get("valid"));
        assertTrue(((List<String>) result.get("errors")).stream().anyMatch(value -> value.contains("站外交易")));
        assertTrue(((List<Map<String, Object>>) result.get("fieldErrors")).stream()
                .anyMatch(item -> "description".equals(item.get("field")) && "PROHIBITED_TERM".equals(item.get("code"))));
        assertTrue(result.get("contentPolicy") instanceof Map);
        assertThrows(com.xianyusmart.exception.BusinessException.class,
                () -> service.requireValidForPublish(payload));
    }

    @Test
    void readsMysqlDatetimeAsEitherLocalDateTimeOrTimestamp() {
        LocalDateTime local = LocalDateTime.of(2026, 9, 15, 16, 30, 0);
        Instant expected = local.atZone(ZoneId.systemDefault()).toInstant();

        assertTrue(expected.equals(ListingDraftService.databaseInstant(local)));
        assertTrue(expected.equals(ListingDraftService.databaseInstant(Timestamp.from(expected))));
    }

    private Map<String, Object> validVirtual() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productType", "VIRTUAL");
        payload.put("name", "Office 与 WPS 插件测试商品");
        payload.put("description", "七天有效期，包含安装教程和售后群支持。");
        payload.put("amount", "9.90");
        payload.put("stock", 100);
        payload.put("images", List.of("https://example.test/item.jpg"));
        payload.put("shippingMode", "ONLINE_DELIVERY");
        payload.put("fulfillmentMode", "AUTO_DELIVERY");
        payload.put("validityDays", 7);
        payload.put("supportPolicy", "通过售后群提供安装与使用支持。");
        payload.put("afterSalesPolicy", "按商品描述与平台规则处理售后。");
        payload.put("industryCode", "SOFTWARE");
        payload.put("leafCategoryCode", "OFFICE_PLUGIN");
        return payload;
    }
}
