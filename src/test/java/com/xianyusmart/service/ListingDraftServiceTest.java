package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingDraftServiceTest {

    @Test
    void acceptsCompleteVirtualListingAndKeepsAdvancedFieldsAsWarnings() {
        Map<String, Object> payload = validVirtual();
        payload.put("leafCategoryCode", "OFFICE_PLUGIN");
        payload.put("skus", List.of(Map.of("key", "Office", "price", 9.9, "stock", 10)));
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

    private Map<String, Object> validVirtual() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productType", "VIRTUAL");
        payload.put("name", "Office 与 WPS 插件测试商品");
        payload.put("description", "七天有效期，包含安装教程和售后群支持。");
        payload.put("amount", "9.90");
        payload.put("stock", 100);
        payload.put("images", List.of("https://example.test/item.jpg"));
        payload.put("shippingMode", "ONLINE_DELIVERY");
        return payload;
    }
}
