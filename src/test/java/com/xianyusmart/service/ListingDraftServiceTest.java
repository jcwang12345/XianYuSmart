package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListingDraftServiceTest {

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
