package com.xianyusmart.service.impl;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.TriggerAutoDeliveryReqDTO;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.AutoDeliveryService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.DeliveryTaskService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.delivery.DeliveryStrategyResolver;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceCardDelegationTest {
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final AutoDeliveryService normalDelivery = mock(AutoDeliveryService.class);
    private final DeliveryStrategyResolver legacyResolver = mock(DeliveryStrategyResolver.class);
    private final KamiConfigService kami = mock(KamiConfigService.class);
    private final BuyerMessageService messages = mock(BuyerMessageService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final XianyuApiCallUtils platform = mock(XianyuApiCallUtils.class);
    private final DeliveryTaskService tasks = mock(DeliveryTaskService.class);
    private final OrderServiceImpl service = spy(new OrderServiceImpl());
    private final XianyuGoodsOrder existing = new XianyuGoodsOrder();
    private final XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();

    @BeforeEach
    void setUp() {
        existing.setId(10L);
        existing.setXianyuAccountId(1L);
        existing.setXyGoodsId("goods-1");
        existing.setOrderId("order-1");
        config.setDeliveryMode(2);
        config.setKamiConfigIds("3");
        when(orders.selectByAccountIdAndOrderId(1L, "order-1")).thenReturn(existing);
        when(configs.findByAccountIdAndGoodsIdNoSku(1L, "goods-1")).thenReturn(config);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configs);
        ReflectionTestUtils.setField(service, "autoDeliveryService", normalDelivery);
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", legacyResolver);
        ReflectionTestUtils.setField(service, "kamiConfigService", kami);
        ReflectionTestUtils.setField(service, "buyerMessageService", messages);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "xianyuApiCallUtils", platform);
        ReflectionTestUtils.setField(service, "deliveryTaskService", tasks);
    }

    @Test
    void cardModeDelegatesExactTupleWithoutEnteringLegacyCardOrPlatformPath() {
        when(normalDelivery.triggerAutoDelivery(any())).thenReturn(ResultObject.success("normal leased result"));
        assertEquals("normal leased result", service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertExactNormalTrigger(1L, "goods-1", "order-1");
        assertNoLegacySideEffects();
    }

    @Test
    void skuSpecificCardConfigStillDelegatesRatherThanResolvingLegacyContent() {
        existing.setSkuId("sku-1");
        when(configs.findByAccountIdAndGoodsIdAndSkuId(1L, "goods-1", "sku-1")).thenReturn(config);
        when(normalDelivery.triggerAutoDelivery(any())).thenReturn(ResultObject.success("strict SKU result"));
        assertEquals("strict SKU result", service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertExactNormalTrigger(1L, "goods-1", "order-1");
        verify(configs, never()).findByAccountIdAndGoodsIdNoSku(anyLong(), anyString());
        assertNoLegacySideEffects();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 201, 400, 403, 404, 409, 500})
    void anyNon200NormalResultFailsAs409WithoutFallback(Integer code) {
        when(normalDelivery.triggerAutoDelivery(any())).thenReturn(new ResultObject<>(code, "strict gate rejected", null));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("strict gate rejected"));
        assertNoLegacySideEffects();
    }

    @Test
    void missingNormalResponseFailsAs409WithoutFallback() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertEquals(409, error.getCode());
        assertNoLegacySideEffects();
    }

    @Test
    void unexpectedNormalExceptionFailsAs409WithoutExposingDetailsOrFallback() {
        when(normalDelivery.triggerAutoDelivery(any())).thenThrow(new IllegalStateException("internal exception detail"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertEquals(409, error.getCode());
        assertFalse(error.getMessage().contains("internal exception detail"));
        assertNoLegacySideEffects();
    }

    @Test
    void missingLocalRecordCannotCreateLegacyOrderOrReserveCards() {
        when(orders.selectByAccountIdAndOrderId(1L, "order-1")).thenReturn(null);
        when(normalDelivery.triggerAutoDelivery(any())).thenReturn(ResultObject.failed("发货记录不存在"));
        assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        assertExactNormalTrigger(1L, "goods-1", "order-1");
        assertNoLegacySideEffects();
        verify(orders, never()).insert(any(XianyuGoodsOrder.class));
    }

    @Test
    void requestedGoodsMismatchUsesNormalExactTupleGate() {
        when(configs.findByAccountIdAndGoodsIdNoSku(1L, "other-goods")).thenReturn(config);
        AutoDeliveryServiceImpl realNormalDelivery = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(realNormalDelivery, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "autoDeliveryService", realNormalDelivery);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(1L, "other-goods", "order-1"));
        assertEquals(409, error.getCode());
        verify(orders).selectByOrderId(1L, "other-goods", "order-1");
        assertNoLegacySideEffects();
    }

    @Test
    void requestedAccountMismatchUsesNormalExactTupleGate() {
        when(configs.findByAccountIdAndGoodsIdNoSku(2L, "goods-1")).thenReturn(config);
        AutoDeliveryServiceImpl realNormalDelivery = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(realNormalDelivery, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "autoDeliveryService", realNormalDelivery);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.consignDummyDeliveryWithConfig(2L, "goods-1", "order-1"));
        assertEquals(409, error.getCode());
        verify(orders).selectByOrderId(2L, "goods-1", "order-1");
        assertNoLegacySideEffects();
    }

    @Test
    void injectionIsLazyToAvoidOrderAndAutoDeliveryCycle() throws Exception {
        assertNotNull(OrderServiceImpl.class.getDeclaredField("autoDeliveryService").getAnnotation(Lazy.class));
    }

    @Test
    void fixedTemplateModeRetainsLegacyResolverAndDoesNotDelegate() {
        config.setDeliveryMode(1);
        assertNull(service.consignDummyDeliveryWithConfig(1L, "goods-1", "order-1"));
        verify(legacyResolver).resolve(eq(1), argThat(context ->
                context.getAccountId().equals(1L) && context.getXyGoodsId().equals("goods-1")
                        && context.getOrderId().equals("order-1")));
        verifyNoInteractions(normalDelivery, kami, messages, accounts, platform, tasks);
    }

    private void assertExactNormalTrigger(Long account, String goods, String order) {
        ArgumentCaptor<TriggerAutoDeliveryReqDTO> request = ArgumentCaptor.forClass(TriggerAutoDeliveryReqDTO.class);
        verify(normalDelivery).triggerAutoDelivery(request.capture());
        assertEquals(account, request.getValue().getXianyuAccountId());
        assertEquals(goods, request.getValue().getXyGoodsId());
        assertEquals(order, request.getValue().getOrderId());
        assertEquals(Boolean.FALSE, request.getValue().getNeedHumanLikeDelay());
    }

    private void assertNoLegacySideEffects() {
        verifyNoInteractions(legacyResolver, kami, messages, accounts, platform, tasks);
        verify(service, never()).consignDummyDelivery(any(), any(), any(), any());
    }
}
