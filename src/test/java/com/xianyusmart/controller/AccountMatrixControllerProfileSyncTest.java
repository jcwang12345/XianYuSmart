package com.xianyusmart.controller;

import com.xianyusmart.service.AccountBatchService;
import com.xianyusmart.service.AccountMatrixService;
import com.xianyusmart.service.ShopProfileSyncService;
import com.xianyusmart.service.ShopRiskSyncService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AccountMatrixControllerProfileSyncTest {

    private final ShopProfileSyncService sync = mock(ShopProfileSyncService.class);
    private final AccountMatrixController controller = new AccountMatrixController(
            mock(AccountMatrixService.class), mock(AccountBatchService.class), sync);

    @Test
    void acceptedStartUses202AndRunningProtocolState() {
        when(sync.start(7L, "r1")).thenReturn(Map.of("status", "RUNNING", "requestId", "r1"));
        var response = controller.syncProfile(7L, new AccountMatrixController.ProfileSyncRequest("r1"));
        assertEquals(202, response.getStatusCode().value());
        assertEquals("RUNNING", response.getBody().getData().get("status"));
    }

    @Test
    void busyStartUsesHttp409() {
        when(sync.start(7L, "r2")).thenReturn(Map.of("status", "BUSY", "requestId", "r2"));
        var response = controller.syncProfile(7L, new AccountMatrixController.ProfileSyncRequest("r2"));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("BUSY", response.getBody().getData().get("status"));
    }

    @Test
    void riskSyncBusyUsesHttp409() {
        ShopRiskSyncService riskSync = mock(ShopRiskSyncService.class);
        AccountMatrixController completeController = new AccountMatrixController(
                mock(AccountMatrixService.class), mock(AccountBatchService.class), sync, riskSync, null);
        when(riskSync.sync(7L, "risk-2")).thenReturn(Map.of("status", "BUSY", "requestId", "risk-2"));

        var response = completeController.syncRisks(7L, new AccountMatrixController.ProfileSyncRequest("risk-2"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("BUSY", response.getBody().getData().get("status"));
    }
}
