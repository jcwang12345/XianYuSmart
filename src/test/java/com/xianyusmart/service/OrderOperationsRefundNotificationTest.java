package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.OrderOperationsMapper;
import com.xianyusmart.mapper.OrderConfirmationMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderOperationsRefundNotificationTest {
    @Test
    void readOnlyRefundingStatusCreatesTheSameDeduplicatedRefundNotice() {
        OrderOperationsMapper operations = mock(OrderOperationsMapper.class);
        XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        OrderDetailFetcher details = mock(OrderDetailFetcher.class);
        WebSocketTokenService tokens = mock(WebSocketTokenService.class);
        RefundObservationService refunds = mock(RefundObservationService.class);
        OrderOperationsService service = new OrderOperationsService(operations, orders, accounts,
                mock(OrderConfirmationMapper.class), details, tokens);
        ReflectionTestUtils.setField(service, "refunds", refunds);

        XianyuAccount account = new XianyuAccount();
        account.setId(1L); account.setTenantId(6L);
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setId(20L); order.setOrderId("order-1"); order.setXyGoodsId("goods-1");
        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.tradeStatus = "REFUNDING";
        when(accounts.selectReconnectableAccounts()).thenReturn(List.of(account));
        when(operations.due(1L)).thenReturn(List.of(order));
        when(details.fetch(1L, "goods-1", "order-1")).thenReturn(detail);

        service.reconcile();

        verify(refunds).observeStatus(1L, "order-1", null, "REFUNDING");
        verifyNoInteractions(orders);
    }
}
