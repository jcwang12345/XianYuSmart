package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductBatchQaMockServiceTest {

    @Test
    void refusesToStartOutsideQaProfile() {
        ProductBatchQaMockService service = new ProductBatchQaMockService(
                new MockEnvironment().withProperty("spring.profiles.active", "prod"), new ObjectMapper(),
                true, 1L, "101", "QA-");
        assertThrows(IllegalStateException.class, service::validateSafetyBoundary);
    }

    @Test
    void requiresTenantAccountAndGoodsPrefixTogether() {
        ProductBatchQaMockService service = service();
        assertTrue(service.isEligible(1L, 101L, "QA-GOODS-0001"));
        assertFalse(service.isEligible(2L, 101L, "QA-GOODS-0001"));
        assertFalse(service.isEligible(1L, 999L, "QA-GOODS-0001"));
        assertFalse(service.isEligible(1L, 101L, "REAL-GOODS-0001"));
        assertEquals(false, service.publicConfiguration().get("platformNetworkCalls"));
    }

    @Test
    void mixedScenarioPersistsDeterministicSuccessFailureAndUnknownBoundaries() {
        ProductBatchQaMockService service = service();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("operation_params_json", "{\"qaScenario\":\"MIXED_100\",\"qaBypassRateLimit\":true}");

        assertTrue(Boolean.TRUE.equals(service.execute(job, item("QA-GOODS-0079", 0)).get("success")));
        assertThrows(ProductBatchQaMockService.ManualRetryRequiredException.class,
                () -> service.execute(job, item("QA-GOODS-0080", 0)));
        assertTrue(Boolean.TRUE.equals(service.execute(job, item("QA-GOODS-0080", 1)).get("success")));
        assertThrows(IllegalStateException.class, () -> service.execute(job, item("QA-GOODS-0095", 0)));
        assertTrue(service.bypassRateLimit(job));
    }

    private ProductBatchQaMockService service() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("qa");
        ProductBatchQaMockService service = new ProductBatchQaMockService(
                environment, new ObjectMapper(), true, 1L, "101,102,103", "QA-");
        service.validateSafetyBoundary();
        return service;
    }

    private Map<String, Object> item(String goodsId, int attempts) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("xianyu_account_id", 101L);
        item.put("xy_goods_id", goodsId);
        item.put("operation_type", "SYNC");
        item.put("attempt_count", attempts);
        return item;
    }
}
