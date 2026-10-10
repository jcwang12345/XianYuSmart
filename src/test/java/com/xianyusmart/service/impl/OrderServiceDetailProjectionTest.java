package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceDetailProjectionTest {
    private final AccountService accounts = mock(AccountService.class);
    private final XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final OrderServiceImpl service = new OrderServiceImpl();
    private final XianyuGoodsOrder order = new XianyuGoodsOrder();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "xianyuApiCallUtils", api);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        order.setId(188L);
        order.setXianyuAccountId(101L);
        order.setOrderId("ORDER-1");
        order.setXyGoodsId("GOODS-1");
        order.setBuyerUserId("BUYER-1");
        when(accounts.getCookieByAccountId(101L)).thenReturn("test-cookie");
        when(orders.selectByAccountIdAndOrderId(101L, "ORDER-1")).thenReturn(order);
    }

    @Test
    void recognizedPaidStatusUpdatesWorkbenchWithoutMarkingDelivered() throws Exception {
        respond("待发货", "0.01", "BUYER-1", "GOODS-1");

        assertNotNull(service.getOrderDetail(101L, "ORDER-1"));

        verify(orders).updateVerifiedOrderSnapshot(188L, 101L, "ORDER-1", "GOODS-1",
                "PAID", new BigDecimal("0.01"));
        verify(orders, never()).updateStateContentAndFailReason(any(), any(), any(), any());
    }

    @Test
    void unknownStatusIsNotInferredFromPaymentTime() throws Exception {
        respond("平台未给出状态", "0.01", "BUYER-1", "GOODS-1");

        assertNotNull(service.getOrderDetail(101L, "ORDER-1"));

        verify(orders, never()).updateVerifiedOrderSnapshot(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mismatchedBuyerOrGoodsCannotUpdateLocalOrder() throws Exception {
        respond("待发货", "0.01", "OTHER-BUYER", "GOODS-1");
        assertNotNull(service.getOrderDetail(101L, "ORDER-1"));
        verify(orders, never()).updateVerifiedOrderSnapshot(any(), any(), any(), any(), any(), any());
        verify(orders, never()).updateOrderDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        respond("待发货", "0.01", "BUYER-1", "OTHER-GOODS");
        assertNotNull(service.getOrderDetail(101L, "ORDER-1"));
        verify(orders, never()).updateVerifiedOrderSnapshot(any(), any(), any(), any(), any(), any());
    }

    @Test
    void amountRejectsUnverifiedPrecisionAndMalformedValues() {
        assertNull(OrderServiceImpl.verifiedAmount("1.234"));
        assertNull(OrderServiceImpl.verifiedAmount("¥0.01"));
        assertNull(OrderServiceImpl.verifiedAmount("-1"));
        assertEquals(new BigDecimal("0.01"), OrderServiceImpl.verifiedAmount("0.01"));
    }

    @Test
    void ordinarySellerCommonNumericIdentityAndBuyerIdUpdateExactLocalOrder() throws Exception {
        prepareOrdinaryLocalOrder();
        respondModule(ordinaryModule(1088432591594L, "BUYER-1"));

        assertNotNull(service.getOrderDetail(101L, "5127502862002001606"));

        verify(orders).updateVerifiedOrderSnapshot(188L, 101L, "5127502862002001606", "1088432591594",
                "PAID", new BigDecimal("0.01"));
        verify(orders).updateOrderDetail(eq(188L), eq("BUYER-1"), any(), any(), any(), any(), any(), any(), any(), eq("0.01"), eq(1));
        verify(orders, never()).updateStateContentAndFailReason(any(), any(), any(), any());
    }

    @Test
    void ordinaryCommonGoodsMismatchCannotUpdateEvenWhenItemModuleMatchesLocalGoods() throws Exception {
        prepareOrdinaryLocalOrder();
        Map<String, Object> module = ordinaryModule(999L, "BUYER-1");
        module.put("merchantItemVO", Map.of("itemId", 1088432591594L, "title", "synthetic item"));
        respondModule(module);

        assertNotNull(service.getOrderDetail(101L, "5127502862002001606"));

        assertNoProjectionUpdate();
    }

    @Test
    void ordinaryBuyerIdMismatchCannotUpdateLocalOrder() throws Exception {
        prepareOrdinaryLocalOrder();
        respondModule(ordinaryModule(1088432591594L, "OTHER-BUYER"));

        assertNotNull(service.getOrderDetail(101L, "5127502862002001606"));

        assertNoProjectionUpdate();
    }

    @Test
    void itemModuleConflictStillRejectsWhenCommonNumericGoodsIsCorrect() throws Exception {
        prepareOrdinaryLocalOrder();
        Map<String, Object> module = ordinaryModule(1088432591594L, "BUYER-1");
        module.put("merchantItemVO", Map.of("itemId", 999L, "title", "synthetic item"));
        respondModule(module);

        assertNotNull(service.getOrderDetail(101L, "5127502862002001606"));

        assertNoProjectionUpdate();
    }

    private void prepareOrdinaryLocalOrder() {
        order.setOrderId("5127502862002001606");
        order.setXyGoodsId("1088432591594");
        when(orders.selectByAccountIdAndOrderId(101L, "5127502862002001606")).thenReturn(order);
    }

    private Map<String, Object> ordinaryModule(long goods, String buyer) {
        Map<String, Object> module = new HashMap<>();
        module.put("merchantCommonData", Map.of("orderId", 5127502862002001606L, "itemId", goods,
                "orderStatus", "待发货", "paySuccessTime", "2026-10-09 14:34:35"));
        module.put("merchantBuyerVO", Map.of("buyerId", buyer, "userNick", "synthetic buyer"));
        module.put("merchantItemVO", Map.of("title", "synthetic item"));
        module.put("merchantPriceVO", Map.of("totalPrice", "0.01", "buyNum", 1));
        return module;
    }

    private void assertNoProjectionUpdate() {
        verify(orders, never()).updateVerifiedOrderSnapshot(any(), any(), any(), any(), any(), any());
        verify(orders, never()).updateOrderDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void respond(String status, String amount, String buyer, String goods) throws Exception {
        Map<String, Object> module = Map.of(
                "merchantCommonData", Map.of("orderId", "ORDER-1", "orderStatus", status,
                        "paySuccessTime", "2026-10-04 10:00:00"),
                "merchantBuyerVO", Map.of("userId", buyer, "userNick", "测试买家"),
                "merchantItemVO", Map.of("itemId", goods, "title", "测试商品"),
                "merchantPriceVO", Map.of("totalPrice", amount, "buyNum", "1"));
        respondModule(module);
    }

    private void respondModule(Map<String, Object> module) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("data", Map.of("module", module)));
        when(api.callApiWithRetry(eq(101L), eq("mtop.taobao.idle.trade.merchant.full.info"),
                anyMap(), eq("test-cookie")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, json, null, false));
    }
}
