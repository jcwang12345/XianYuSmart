package com.xianyusmart.event.chatMessageEvent.lister;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.BuyerProfileService;
import com.xianyusmart.service.DeliveryTaskService;
import com.xianyusmart.service.MerchantOperationsService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventAutoDeliveryListenerTest {

    @Mock
    private XianyuGoodsInfoMapper goodsInfoMapper;
    @Mock
    private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock
    private DeliveryTaskService deliveryTaskService;
    @Mock
    private BuyerProfileService buyerProfileService;
    @Mock
    private NotificationCenterService notificationCenterService;
    @Mock
    private OrderService orderService;
    @Mock
    private MerchantOperationsService merchantOperationsService;

    @InjectMocks
    private ChatMessageEventAutoDeliveryListener listener;

    private ChatMessageReceivedEvent event;

    @BeforeEach
    void setUp() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setPnmId("payment-message-1");
        message.setContentType(26);
        message.setMsgContent("[已付款，待发货]");
        message.setXyGoodsId("goods-1");
        message.setSId("buyer-1@goofish");
        message.setSenderUserId("buyer-1");
        message.setSenderUserName("buyer");
        message.setOrderId("order-1");
        event = new ChatMessageReceivedEvent(this, message);

        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setId(10L);
        when(goodsInfoMapper.selectOne(any(QueryWrapper.class))).thenReturn(goods);

        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoDeliveryOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(1L, "goods-1")).thenReturn(config);
        when(buyerProfileService.automationBlockReason(1L, "buyer-1")).thenReturn(null);

        XianyuGoodsOrder persisted = new XianyuGoodsOrder();
        persisted.setId(100L);
        when(deliveryTaskService.discover(any(XianyuGoodsOrder.class), any())).thenReturn(persisted);
    }

    @Test
    void dispatchesOrderCreatedOnlyWhenNotificationClaimSucceeds() {
        when(deliveryTaskService.claimOrderCreatedNotification(100L)).thenReturn(true);

        listener.handleChatMessageReceived(event);

        verify(notificationCenterService).dispatch(eq("ORDER_CREATED"), eq(1L),
                eq("发现新的待发货订单"), any(), any());
    }

    @Test
    void skipsOrderCreatedWhenReconnectReplaysExistingOrder() {
        when(deliveryTaskService.claimOrderCreatedNotification(100L)).thenReturn(false);

        listener.handleChatMessageReceived(event);

        verify(notificationCenterService, never()).dispatch(eq("ORDER_CREATED"), anyLong(),
                any(), any(), any());
    }
}
