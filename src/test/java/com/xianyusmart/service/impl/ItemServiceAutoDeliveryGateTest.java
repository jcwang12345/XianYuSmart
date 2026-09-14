package com.xianyusmart.service.impl;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.UpdateAutoDeliveryReqDTO;
import com.xianyusmart.controller.dto.UpdateAutoDeliveryRespDTO;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.service.AutoDeliveryService;
import com.xianyusmart.service.GoodsSkuReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceAutoDeliveryGateTest {

    @Mock
    private GoodsSkuReadinessService readinessService;
    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper configMapper;
    @Mock
    private AutoDeliveryService autoDeliveryService;

    private ItemServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ItemServiceImpl();
        ReflectionTestUtils.setField(service, "goodsSkuReadinessService", readinessService);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "autoDeliveryService", autoDeliveryService);
    }

    @Test
    void blocksEnableWhenSkuSnapshotIsPartial() {
        when(readinessService.requireComplete(101L, "G-1"))
                .thenThrow(new IllegalStateException("主档4个，已验证2个"));

        ResultObject<UpdateAutoDeliveryRespDTO> result = service.updateAutoDeliveryStatus(request(1));

        assertEquals(500, result.getCode());
        assertTrue(result.getMsg().contains("已验证2个"));
        verify(autoDeliveryService, never()).saveOrUpdateGoodsConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blocksEnableUntilEveryVerifiedSkuHasConfig() {
        when(readinessService.requireComplete(101L, "G-1"))
                .thenReturn(new GoodsSkuReadinessService.Readiness(4, 4,
                        GoodsSkuReadinessService.Status.FULL, "完整"));
        when(configMapper.findByAccountIdAndGoodsId(101L, "G-1"))
                .thenReturn(List.of(config("SKU-1"), config("SKU-2")));

        ResultObject<UpdateAutoDeliveryRespDTO> result = service.updateAutoDeliveryStatus(request(1));

        assertEquals(500, result.getCode());
        assertTrue(result.getMsg().contains("2/4"));
        verify(autoDeliveryService, never()).saveOrUpdateGoodsConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enablesAfterAllVerifiedSkusAreConfigured() {
        when(readinessService.requireComplete(101L, "G-1"))
                .thenReturn(new GoodsSkuReadinessService.Readiness(4, 4,
                        GoodsSkuReadinessService.Status.FULL, "完整"));
        when(configMapper.findByAccountIdAndGoodsId(101L, "G-1"))
                .thenReturn(List.of(config("SKU-1"), config("SKU-2"), config("SKU-3"), config("SKU-4")));
        when(autoDeliveryService.getGoodsConfig(101L, "G-1")).thenReturn(null);

        ResultObject<UpdateAutoDeliveryRespDTO> result = service.updateAutoDeliveryStatus(request(1));

        assertEquals(200, result.getCode());
        verify(autoDeliveryService).saveOrUpdateGoodsConfig(org.mockito.ArgumentMatchers.argThat(value ->
                value.getXianyuAutoDeliveryOn() == 1 && value.getXianyuAccountId().equals(101L)));
    }

    private UpdateAutoDeliveryReqDTO request(int enabled) {
        UpdateAutoDeliveryReqDTO request = new UpdateAutoDeliveryReqDTO();
        request.setXianyuAccountId(101L);
        request.setXyGoodsId("G-1");
        request.setXianyuAutoDeliveryOn(enabled);
        return request;
    }

    private XianyuGoodsAutoDeliveryConfig config(String skuId) {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setSkuId(skuId);
        return config;
    }
}
