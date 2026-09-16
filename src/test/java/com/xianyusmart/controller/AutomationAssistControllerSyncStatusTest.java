package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsSkuMapper;
import com.xianyusmart.service.GoodsSkuService;
import com.xianyusmart.service.ItemDetailSyncService;
import com.xianyusmart.service.OrderConfirmationService;
import com.xianyusmart.service.OrderOperationsService;
import com.xianyusmart.service.reply.ReplyEnhancementService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationAssistControllerSyncStatusTest {

    @ParameterizedTest
    @MethodSource("failureStatuses")
    void mapsUnifiedSyncFailureStatus(ItemDetailSyncService.SyncStatus status, int expectedCode) {
        GoodsSkuService skus = mock(GoodsSkuService.class);
        ItemDetailSyncService sync = mock(ItemDetailSyncService.class);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        when(accounts.selectById(7L)).thenReturn(new XianyuAccount());
        when(sync.syncSingleItemWithResult(7L, "goods-1"))
                .thenReturn(new ItemDetailSyncService.SyncResult(status, "可执行提示", null));
        AutomationAssistController controller = new AutomationAssistController(
                mock(ReplyEnhancementService.class), mock(OrderOperationsService.class),
                mock(OrderConfirmationService.class), skus, sync, accounts,
                mock(XianyuGoodsSkuMapper.class));

        com.xianyusmart.context.TenantContext.set(1L);
        try {
            ResultObject<List<XianyuGoodsSku>> result = controller.sync(
                    new AutomationAssistController.GoodsTarget(7L, "goods-1"));

            assertEquals(expectedCode, result.getCode());
            assertEquals("可执行提示", result.getMsg());
            verify(skus, never()).listByXyGoodsId("goods-1", 7L);
        } finally {
            com.xianyusmart.context.TenantContext.clear();
        }
    }

    private static Stream<Arguments> failureStatuses() {
        return Stream.of(
                Arguments.of(ItemDetailSyncService.SyncStatus.BUSY, 409),
                Arguments.of(ItemDetailSyncService.SyncStatus.VERIFICATION_REQUIRED, 409),
                Arguments.of(ItemDetailSyncService.SyncStatus.FORBIDDEN, 403),
                Arguments.of(ItemDetailSyncService.SyncStatus.NOT_FOUND, 404),
                Arguments.of(ItemDetailSyncService.SyncStatus.UNAVAILABLE, 503));
    }
}
