package com.xianyusmart.event.chatMessageEvent.lister;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.RefundObservationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundNotificationListenerTest {
    @Test
    void verifiedRefundCardStopsPendingDeliveryAndCreatesActionableNotice() {
        Fixture fixture = new Fixture();
        fixture.listener.handleChatMessageReceived(fixture.event("order-1"));
        verify(fixture.refunds).observeMessage(any(ChatMessageData.class));
    }

    @Test
    void refundCardWithoutOrderIdNotifiesVerificationButDoesNotChangeOrder() {
        Fixture fixture = new Fixture();

        fixture.listener.handleChatMessageReceived(fixture.event(null));

        verifyNoInteractions(fixture.orders, fixture.kami);
        verify(fixture.refunds).observeMessage(any(ChatMessageData.class));
    }

    @Test
    void ordinaryBuyerTextMentioningRefundDoesNotClaimAPlatformRefund() {
        Fixture fixture = new Fixture();
        ChatMessageReceivedEvent event = fixture.event("order-1");
        event.getMessageData().setContentType(1);

        fixture.listener.handleChatMessageReceived(event);

        verifyNoInteractions(fixture.orders, fixture.notifications, fixture.refunds);
    }

    private static class Fixture {
        final ChatMessageEventAutoDeliveryListener listener = new ChatMessageEventAutoDeliveryListener();
        final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
        final KamiConfigService kami = mock(KamiConfigService.class);
        final NotificationCenterService notifications = mock(NotificationCenterService.class);
        final RefundObservationService refunds = mock(RefundObservationService.class);

        Fixture() {
            ReflectionTestUtils.setField(listener, "orderMapper", orders);
            ReflectionTestUtils.setField(listener, "kamiConfigService", kami);
            ReflectionTestUtils.setField(listener, "notificationCenterService", notifications);
            ReflectionTestUtils.setField(listener, "refundObservationService", refunds);
        }

        ChatMessageReceivedEvent event(String orderId) {
            ChatMessageData message = new ChatMessageData();
            message.setXianyuAccountId(1L);
            message.setPnmId("refund-card-1");
            message.setSId("session-1");
            message.setXyGoodsId("goods-1");
            message.setOrderId(orderId);
            message.setContentType(26);
            message.setMsgContent("我发起了退款申请");
            return new ChatMessageReceivedEvent(this, message);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> notificationData() {
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(notifications).dispatch(eq("REFUND_REQUESTED"), eq(1L), anyString(), anyString(), data.capture());
            return data;
        }
    }
}
