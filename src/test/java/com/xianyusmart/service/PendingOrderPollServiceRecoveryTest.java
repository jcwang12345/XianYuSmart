package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PendingOrderPollServiceRecoveryTest {

    @Test
    void reconnectDoesNotAccelerateRiskWaitOrReopenExistingOrders() {
        for (String status : List.of("RETRY_WAIT", "FAILED", "REVIEW_REQUIRED", "COMPLETED")) {
            PendingOrderPollService service = new PendingOrderPollService();
            OrderService orders = mock(OrderService.class);
            XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
            XianyuGoodsConfigMapper configs = mock(XianyuGoodsConfigMapper.class);
            DeliveryTaskService deliveryTasks = mock(DeliveryTaskService.class);
            ReflectionTestUtils.setField(service, "orderService", orders);
            ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
            ReflectionTestUtils.setField(service, "goodsConfigMapper", configs);
            ReflectionTestUtils.setField(service, "deliveryTaskService", deliveryTasks);
            ReflectionTestUtils.setField(service, "buyerProfileService", mock(BuyerProfileService.class));
            AccountPolicyFixture.active(service);

            XianyuGoodsConfig config = new XianyuGoodsConfig();
            config.setXianyuAutoDeliveryOn(1);
            XianyuGoodsOrder existing = new XianyuGoodsOrder();
            existing.setId(188L);
            existing.setDeliveryStatus(status);
            existing.setNextRetryTime(LocalDateTime.now().plusHours(2));
            when(configs.selectByAccountAndGoodsId(7L, "goods-1")).thenReturn(config);
            when(orderMapper.selectByAccountIdAndOrderId(7L, "order-1")).thenReturn(existing);
            when(orders.queryPendingOrders(7L)).thenReturn(List.of(Map.of(
                    "commonData", Map.of("orderId", "order-1", "itemId", "goods-1", "orderStatus", "待发货"))));

            assertEquals(0, service.deliverPendingOrders(7L), status);
            verifyNoInteractions(deliveryTasks);
            verify(orderMapper, never()).insert(any());
        }
    }
}
