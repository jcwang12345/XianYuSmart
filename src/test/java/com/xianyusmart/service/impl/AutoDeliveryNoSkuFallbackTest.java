package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.service.GoodsSkuReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoDeliveryNoSkuFallbackTest {
    private final GoodsSkuReadinessService readiness = mock(GoodsSkuReadinessService.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final AutoDeliveryServiceImpl delivery = new AutoDeliveryServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(delivery, "goodsSkuReadinessService", readiness);
        ReflectionTestUtils.setField(delivery, "autoDeliveryConfigMapper", configs);
        when(readiness.inspect(1L, "item-1")).thenReturn(new GoodsSkuReadinessService.Readiness(
                0, 0, GoodsSkuReadinessService.Status.PARTIAL, "列表快照未完整同步"));
    }

    @Test
    void permitsUniqueGenericFixedTemplateForOrderWithoutSkuEvidence() {
        XianyuGoodsAutoDeliveryConfig generic = fixedConfig(1);
        when(configs.findByAccountIdAndGoodsIdNoSku(1L, "item-1")).thenReturn(generic);

        assertSame(generic, delivery.resolveDeliveryConfig(1L, "item-1", null, null));
        verify(configs).countSkuSpecificByAccountAndGoodsId(1L, "item-1");
    }

    @Test
    void permitsGenericTemplateForTrustedEmptySnapshot() {
        when(readiness.inspect(1L, "item-1")).thenReturn(new GoodsSkuReadinessService.Readiness(
                0, 0, GoodsSkuReadinessService.Status.EMPTY_VERIFIED, "完整可信快照"));
        var generic = fixedConfig(1);
        when(configs.findByAccountIdAndGoodsIdNoSku(1L, "item-1")).thenReturn(generic);
        assertSame(generic, delivery.resolveDeliveryConfig(1L, "item-1", null, null));
    }

    @Test
    void rejectsOrderWithSkuNameEvenWhenIdIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> delivery.resolveDeliveryConfig(1L, "item-1", null, "红色 XL"));
        verify(configs, never()).countSkuSpecificByAccountAndGoodsId(anyLong(), anyString());
    }

    @Test
    void rejectsCardPoolModeWithoutCompleteSkuSnapshot() {
        when(configs.findByAccountIdAndGoodsIdNoSku(1L, "item-1")).thenReturn(fixedConfig(2));
        assertThrows(IllegalStateException.class,
                () -> delivery.resolveDeliveryConfig(1L, "item-1", null, null));
    }

    @Test
    void explicitUniformCardPolicyWorksWithoutPretendingSkuSnapshotIsComplete() {
        var generic=fixedConfig(2);
        when(configs.findByAccountIdAndGoodsIdNoSku(1L,"item-1")).thenReturn(generic);
        when(readiness.permitsUniformCard(1L,"item-1",generic)).thenReturn(true);
        assertSame(generic,delivery.resolveDeliveryConfig(1L,"item-1",null,null));
        assertTrue(generic.isUniformCardDeliveryRequired());
        assertSame(generic,delivery.resolveDeliveryConfig(1L,"item-1","sku-x","Variant"));
        assertEquals(GoodsSkuReadinessService.Status.PARTIAL,readiness.inspect(1L,"item-1").status());
    }

    @Test
    void uniformPolicyAlsoPrecedesIndividualSkuRulesForFullSnapshot() {
        var generic=fixedConfig(2);
        when(readiness.inspect(1L,"item-1")).thenReturn(new GoodsSkuReadinessService.Readiness(
                2,2,GoodsSkuReadinessService.Status.FULL,"verified"));
        when(configs.findByAccountIdAndGoodsIdNoSku(1L,"item-1")).thenReturn(generic);
        when(readiness.permitsUniformCard(1L,"item-1",generic)).thenReturn(true);
        assertSame(generic,delivery.resolveDeliveryConfig(1L,"item-1","sku-x","Variant"));
    }

    @Test
    void revokedUniformConsentCannotBeDowngradedToOrdinaryBeforeFirstWrite() {
        var generic=fixedConfig(2); generic.setUniformCardDeliveryRequired(true);
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(delivery,
                "requireUniformPolicyBeforeFirstWrite",1L,"item-1",generic));
    }

    @Test
    void aConfirmedFirstWriteKeepsOriginalPayloadCompletionInsteadOfReallocation() {
        var generic=fixedConfig(2); generic.setUniformCardDeliveryRequired(true);
        try(var scope=new com.xianyusmart.service.delivery.DeliveryExecution("token",()->true,()->true)) {
            com.xianyusmart.service.delivery.DeliveryExecution.beforeExternal();
            assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(delivery,
                    "requireUniformPolicyBeforeFirstWrite",1L,"item-1",generic));
        }
    }

    @Test
    void rejectsWhenVariantSpecificConfigExists() {
        when(configs.countSkuSpecificByAccountAndGoodsId(1L, "item-1")).thenReturn(1);
        assertThrows(IllegalStateException.class,
                () -> delivery.resolveDeliveryConfig(1L, "item-1", null, null));
        verify(configs).findByAccountIdAndGoodsIdNoSku(1L, "item-1");
    }

    private XianyuGoodsAutoDeliveryConfig fixedConfig(int mode) {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(mode);
        config.setFixedTemplateId(1L);
        return config;
    }
}
