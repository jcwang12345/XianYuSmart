package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QaBusinessAnalyticsControllerTest {

    @Test
    void refusesFixtureWhenQaMockIsDisabled() {
        ProductBatchQaMockService qaMock = mock(ProductBatchQaMockService.class);
        when(qaMock.enabled()).thenReturn(false);
        QaBusinessAnalyticsController controller = controller(qaMock);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.fixtures(
                new QaBusinessAnalyticsController.FixtureRequest("qa-dashboard-disabled", 101L, 100, null)));

        assertEquals(404, error.getCode());
    }

    @Test
    void validatesRequestBeforeUsingTenantData() {
        ProductBatchQaMockService qaMock = mock(ProductBatchQaMockService.class);
        when(qaMock.enabled()).thenReturn(true);
        QaBusinessAnalyticsController controller = controller(qaMock);

        BusinessException badRequestId = assertThrows(BusinessException.class, () -> controller.fixtures(
                new QaBusinessAnalyticsController.FixtureRequest("production-fixture", 101L, 100, null)));
        BusinessException badSize = assertThrows(BusinessException.class, () -> controller.fixtures(
                new QaBusinessAnalyticsController.FixtureRequest("qa-dashboard-size", 101L, 101, null)));

        assertEquals(400, badRequestId.getCode());
        assertEquals(400, badSize.getCode());
    }

    private QaBusinessAnalyticsController controller(ProductBatchQaMockService qaMock) {
        return new QaBusinessAnalyticsController(mock(JdbcTemplate.class), qaMock,
                mock(AccountAccessService.class), mock(OperationLogService.class), new ObjectMapper());
    }
}
