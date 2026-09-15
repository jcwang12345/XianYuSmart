package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class GrowthResourceServiceTest {

    private final GrowthResourceService service = new GrowthResourceService(
            mock(JdbcTemplate.class), new ObjectMapper(),
            mock(AccountAccessService.class), mock(OperationLogService.class));

    @Test
    void materialWithoutAmountUsesOnlyInternalStorageSentinel() {
        assertEquals(BigDecimal.ZERO, service.storageAmount("MATERIAL", null));
        assertEquals(BigDecimal.ZERO, service.storageAmount("MATERIAL", ""));
        assertNull(service.exposedAmount("MATERIAL", Map.of(), BigDecimal.ZERO));
    }

    @Test
    void explicitMaterialAmountIsNotHiddenAsUnknown() {
        assertEquals(new BigDecimal("12.30"), service.storageAmount("MATERIAL", "12.30"));
        assertEquals(new BigDecimal("12.30"), service.exposedAmount(
                "MATERIAL", Map.of("amount", new BigDecimal("12.30")), new BigDecimal("12.30")));
    }

    @Test
    void supplyStillRequiresExplicitAmount() {
        assertThrows(BusinessException.class, () -> service.storageAmount("SUPPLY", null));
    }

    @Test
    void supplyRejectsNegativeOrOverPrecisionAmount() {
        assertThrows(BusinessException.class, () -> service.storageAmount("SUPPLY", "-0.01"));
        assertThrows(BusinessException.class, () -> service.storageAmount("SUPPLY", "1.234"));
        assertEquals(new BigDecimal("1.23"), service.storageAmount("SUPPLY", "1.23"));
    }

    @Test
    void detailProjectionUsesTheActiveImmutableVersion() {
        Map<String, Object> draft = Map.of("version", 2, "lifecycleState", "DRAFT");
        Map<String, Object> active = Map.of("version", 1, "lifecycleState", "ACTIVE");

        assertEquals(active, service.activeVersion(List.of(draft, active)));
        assertNull(service.activeVersion(List.of(draft)));
    }
}
