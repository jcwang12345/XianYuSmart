package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsSkuReadinessServiceTest {

    @Mock
    private XianyuGoodsInfoMapper goodsInfoMapper;
    @Mock
    private GoodsSkuService goodsSkuService;

    private GoodsSkuReadinessService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GoodsSkuReadinessService();
        inject("goodsInfoMapper", goodsInfoMapper);
        inject("goodsSkuService", goodsSkuService);
    }

    @Test
    void acceptsFourOfFourAndValidSku() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(4, "FULL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(4);
        XianyuGoodsSku sku = new XianyuGoodsSku();
        sku.setSkuId("SKU-1");
        when(goodsSkuService.findByXyGoodsIdAndSkuId("G-1", 101L, "SKU-1")).thenReturn(sku);

        assertEquals("SKU-1", service.requireValidSelection(101L, "G-1", "SKU-1").getSkuId());
    }

    @Test
    void blocksZeroOfFour() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(4, "PARTIAL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireComplete(101L, "G-1"));
        assertTrue(error.getMessage().contains("4 个 SKU"));
    }

    @Test
    void blocksTwoOfFour() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(4, "PARTIAL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireComplete(101L, "G-1"));
        assertTrue(error.getMessage().contains("已验证 2 个"));
    }

    @Test
    void acceptsVerifiedEmptySnapshotWithoutSkuSelection() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(0, "FULL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(0);

        assertNull(service.requireValidSelection(101L, "G-1", null));
    }

    @Test
    void blocksZeroOfZeroWhenSnapshotIsNotFull() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(0, "PARTIAL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.requireComplete(101L, "G-1"));
    }

    private XianyuGoodsInfo goods(Integer skuCount, String coverage) {
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setSkuCount(skuCount);
        goods.setCoverageStatus(coverage);
        return goods;
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = GoodsSkuReadinessService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
