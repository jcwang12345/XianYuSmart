package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class QaFulfillmentControllerTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void disabledFixtureStopsBeforeDatabaseAccess() {
        QaFulfillmentController controller = controller(false, "101,102,103");
        BusinessException error = assertThrows(BusinessException.class, () -> controller.fixture(
                new QaFulfillmentController.FixtureRequest(101L, "qa-disabled", 25, "OPEN")));
        assertEquals(404, error.getCode());
    }

    @Test void nonWhitelistedTenantIsRejected() {
        TenantContext.set(2L);
        QaFulfillmentController controller = controller(true, "101,102,103");
        BusinessException error = assertThrows(BusinessException.class, () -> controller.fixture(
                new QaFulfillmentController.FixtureRequest(101L, "qa-wrong-tenant", 25, "OPEN")));
        assertEquals(403, error.getCode());
    }

    @Test void realAccountOutsideWhitelistIsRejected() {
        TenantContext.set(1L);
        QaFulfillmentController controller = controller(true, "101,102,103");
        BusinessException error = assertThrows(BusinessException.class, () -> controller.fixture(
                new QaFulfillmentController.FixtureRequest(202L, "qa-real-account", 25, "OPEN")));
        assertEquals(403, error.getCode());
    }

    private QaFulfillmentController controller(boolean enabled, String accounts) {
        Environment environment = mock(Environment.class);
        org.mockito.Mockito.when(environment.getActiveProfiles()).thenReturn(new String[]{"qa"});
        ProductBatchQaMockService qa = new ProductBatchQaMockService(environment, new ObjectMapper(),
                enabled, 1L, accounts, "QA-");
        return new QaFulfillmentController(mock(JdbcTemplate.class), qa,
                mock(AccountAccessService.class), mock(OperationLogService.class));
    }
}
