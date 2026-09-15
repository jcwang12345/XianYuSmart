package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductEventServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void publishEventPersistsFieldDifferencesAndPartialSyncWhenReadBackPending() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductEventService service = new ProductEventService(jdbc, new ObjectMapper());
        TenantContext.set(1L);
        UserContext.set(7L, "qa", 1L);
        Map<String, Object> differences = Map.of(
                "status", "PARTIAL",
                "items", List.of(Map.of("field", "categoryId", "status", "UNAVAILABLE")));

        service.publishCompleted(101L, "QA-1", "request-1", "QA_LOCAL",
                "QA_CONFIRMED", Map.of(
                        "verificationStatus", "PENDING",
                        "localSynced", true,
                        "fieldDifferences", differences), "QA_FIXTURE");

        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO xianyu_goods_event"), eventArgs.capture());
        assertEquals(11, eventArgs.getValue().length);
        assertTrue(String.valueOf(eventArgs.getValue()[10]).contains("categoryId"));

        ArgumentCaptor<Object[]> goodsArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE xianyu_goods"), goodsArgs.capture());
        assertEquals("PARTIAL", goodsArgs.getValue()[2]);
    }
}
