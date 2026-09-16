package com.xianyusmart.service.impl;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.ItemDetailReqDTO;
import com.xianyusmart.controller.dto.ItemDetailRespDTO;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.ItemDetailSyncService;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemServiceDetailIsolationTest {

    @Test
    void rejectsCrossAccountDetailRefreshBeforePlatformRead() {
        GoodsInfoService goodsInfoService = mock(GoodsInfoService.class);
        ItemDetailSyncService syncService = mock(ItemDetailSyncService.class);
        ItemServiceImpl service = new ItemServiceImpl();
        ReflectionTestUtils.setField(service, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(service, "itemDetailSyncService", syncService);
        XianyuGoodsInfo item = new XianyuGoodsInfo();
        item.setXyGoodId("12345678");
        item.setXianyuAccountId(7L);
        when(goodsInfoService.getByXyGoodId("12345678")).thenReturn(item);
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId("12345678");
        request.setCookieId("8");

        ResultObject<ItemDetailRespDTO> result = service.getItemDetail(request);

        assertEquals(403, result.getCode());
        assertTrue(result.getMsg().contains("归属不一致"));
        verify(syncService, never()).syncSingleItemWithResult(8L, "12345678");
        verify(goodsInfoService, never()).updateDetailInfo(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(goodsInfoService, never()).updateSkuCount(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsRefreshWithoutGoodsWritePermissionBeforePlatformRead() {
        GoodsInfoService goodsInfoService = mock(GoodsInfoService.class);
        ItemDetailSyncService syncService = mock(ItemDetailSyncService.class);
        PlatformPermissionService permissionService = mock(PlatformPermissionService.class);
        ItemServiceImpl service = new ItemServiceImpl();
        ReflectionTestUtils.setField(service, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(service, "itemDetailSyncService", syncService);
        ReflectionTestUtils.setField(service, "platformPermissionService", permissionService);
        XianyuGoodsInfo item = new XianyuGoodsInfo();
        item.setXyGoodId("12345678");
        item.setXianyuAccountId(7L);
        item.setDetailInfo("");
        when(goodsInfoService.getByXyGoodId("12345678")).thenReturn(item);
        when(permissionService.hasPermission(99L, PermissionCatalog.ACTION_GOODS_WRITE))
                .thenReturn(false);
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId("12345678");

        com.xianyusmart.context.UserContext.set(99L, "readonly", 1L);
        try {
            ResultObject<ItemDetailRespDTO> result = service.getItemDetail(request);

            assertEquals(403, result.getCode());
            verify(syncService, never()).syncSingleItemWithResult(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        } finally {
            com.xianyusmart.context.UserContext.clear();
        }
    }
}
