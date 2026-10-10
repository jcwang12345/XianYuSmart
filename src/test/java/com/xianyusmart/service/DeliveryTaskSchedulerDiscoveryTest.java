package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

class DeliveryTaskSchedulerDiscoveryTest {

    @Test
    void pollsEnabledAccountEvenWhenWebSocketIsOnline() {
        XianyuAccount account = new XianyuAccount();
        account.setId(1L);
        account.setTenantId(1L);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        when(accounts.selectReconnectableAccounts()).thenReturn(List.of(account));
        OrderService orders = mock(OrderService.class);
        when(orders.queryPendingOrders(1L)).thenReturn(List.of());
        WebSocketService webSocket = mock(WebSocketService.class);
        when(webSocket.isConnected(1L)).thenReturn(true);
        XianyuGoodsConfigMapper configs = mock(XianyuGoodsConfigMapper.class);
        when(configs.countAutoDeliveryEnabledByAccountId(1L)).thenReturn(1);

        DeliveryTaskScheduler scheduler = scheduler(accounts, orders, webSocket);
        ReflectionTestUtils.setField(scheduler, "goodsConfigMapper", configs);
        scheduler.discoverOrdersFromApi();

        verify(orders).queryPendingOrders(1L);
        verifyNoInteractions(webSocket);
    }

    @Test
    void skipsAccountsWithoutAnyAutoDeliveryGoods() {
        XianyuAccount account = new XianyuAccount();
        account.setId(2L);
        account.setTenantId(1L);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        when(accounts.selectReconnectableAccounts()).thenReturn(List.of(account));
        OrderService orders = mock(OrderService.class);
        XianyuGoodsConfigMapper configs = mock(XianyuGoodsConfigMapper.class);

        DeliveryTaskScheduler scheduler = scheduler(accounts, orders, mock(WebSocketService.class));
        ReflectionTestUtils.setField(scheduler, "goodsConfigMapper", configs);
        scheduler.discoverOrdersFromApi();

        verifyNoInteractions(orders);
    }

    private DeliveryTaskScheduler scheduler(XianyuAccountMapper accounts, OrderService orders,
                                            WebSocketService webSocket) {
        DeliveryTaskScheduler scheduler = new DeliveryTaskScheduler(mock(DeliveryTaskService.class),
                mock(AutoDeliveryService.class), mock(XianyuGoodsOrderMapper.class),
                mock(XianyuKamiItemMapper.class), accounts, orders,
                mock(PendingOrderPollService.class), webSocket,
                mock(BuyerProfileService.class), Runnable::run);
        ReflectionTestUtils.setField(scheduler, "backgroundPlatformActivityPolicy",
                new BackgroundPlatformActivityPolicy());
        ReflectionTestUtils.setField(scheduler, "leaseService",
                mock(com.xianyusmart.service.delivery.DeliveryLeaseService.class));
        AccountPolicyFixture.active(scheduler);
        return scheduler;
    }
}
