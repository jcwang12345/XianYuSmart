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
    void limitedFixedEligibilityDoesNotDeclareSnapshotCompleteAndRejectsUnsafeNeighbors() throws Exception {
        var mappings = org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper.class);
        var templates = org.mockito.Mockito.mock(FixedDeliveryTemplateService.class);
        inject("deliveryConfigMapper", mappings); inject("fixedDeliveryTemplateService", templates);
        var partial = new GoodsSkuReadinessService.Readiness(0, 0, GoodsSkuReadinessService.Status.PARTIAL, "partial");
        var config = new com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(1); config.setFixedTemplateId(8L);
        when(templates.findOwnedTemplate(101L, 8L)).thenReturn(new com.xianyusmart.entity.XianyuFixedDeliveryTemplate());
        assertTrue(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
        assertFalse(partial.complete());
        for (Integer declared : new Integer[]{null, -1, 1}) assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1",
                new GoodsSkuReadinessService.Readiness(declared, 0, GoodsSkuReadinessService.Status.PARTIAL, "partial"), config));
        assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", new GoodsSkuReadinessService.Readiness(0, 1, GoodsSkuReadinessService.Status.PARTIAL, "partial"), config));
        config.setDeliveryMode(2); assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
        config.setDeliveryMode(1); config.setSkuId("sku"); assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
        config.setSkuId(null); when(mappings.countSkuSpecificByAccountAndGoodsId(101L, "G-1")).thenReturn(1);
        assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
        when(mappings.countSkuSpecificByAccountAndGoodsId(101L, "G-1")).thenReturn(0);
        when(templates.findOwnedTemplate(101L, 8L)).thenReturn(null);
        assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
        config.setFixedTemplateId(null); assertFalse(service.permitsLimitedFixedTemplate(101L, "G-1", partial, config));
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
        assertTrue(error.getMessage().contains("4 个规格"));
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
        assertNull(service.requireConfigSelection(101L, "G-1", null));
    }

    @Test
    void allowsVerifiedChildDraftFromPartialSnapshotButStillBlocksExecution() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(4, "PARTIAL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(2);
        XianyuGoodsSku sku = new XianyuGoodsSku(); sku.setSkuId("SKU-1");
        when(goodsSkuService.findByXyGoodsIdAndSkuId("G-1", 101L, "SKU-1")).thenReturn(sku);
        assertSame(sku, service.requireConfigSelection(101L, "G-1", " SKU-1 "));
        assertThrows(IllegalStateException.class, () -> service.requireValidSelection(101L, "G-1", "SKU-1"));
    }

    @Test
    void rejectsForgedOrCrossAccountChildEvenForDraft() {
        when(goodsInfoMapper.selectOne(any())).thenReturn(goods(4, "PARTIAL"));
        when(goodsSkuService.countByXyGoodsId("G-1", 101L)).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> service.requireConfigSelection(101L, "G-1", "forged"));
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
