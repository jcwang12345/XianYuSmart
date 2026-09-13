package com.xianyusmart.service;

import com.xianyusmart.service.delivery.*;
import com.xianyusmart.service.notification.*;
import com.xianyusmart.service.reply.*;
import com.xianyusmart.entity.*;
import com.xianyusmart.mapper.*;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationSafetyTest {
    @Test void expiredLeaseCannotStartExternalWorkAndScopeIsCleared() {
        AtomicInteger begins=new AtomicInteger();
        try(var scope=new DeliveryExecution("claim",()->false,()->{begins.incrementAndGet();return true;})) {
            assertThrows(DeliveryExecution.LeaseLostException.class,DeliveryExecution::beforeExternal);
            assertEquals(0,begins.get());
        }
        assertNull(DeliveryExecution.token());
    }
    @Test void markerIsPersistedOnlyOnceAndLostLeaseStopsLaterSends() {
        AtomicBoolean active=new AtomicBoolean(true);AtomicInteger begins=new AtomicInteger();
        try(var scope=new DeliveryExecution("claim",active::get,()->{begins.incrementAndGet();return true;})) {
            DeliveryExecution.beforeExternal();DeliveryExecution.beforeExternal();
            assertTrue(DeliveryExecution.started());assertEquals(1,begins.get());
            active.set(false);assertThrows(DeliveryExecution.LeaseLostException.class,DeliveryExecution::beforeExternal);
        }
    }
    @Test void buyerMustMatchAuthoritativeOrder() {
        assertEquals("buyer",OrderRecipientVerifier.requireMatch(null,"buyer"));
        assertThrows(IllegalStateException.class,()->OrderRecipientVerifier.requireMatch("spoofed","buyer"));
        assertThrows(IllegalStateException.class,()->OrderRecipientVerifier.requireMatch("buyer",null));
    }
    @Test void sellerAndRefundCannotReceiveAutomaticDelivery() {
        var accounts=mock(XianyuAccountMapper.class);var accountService=mock(AccountService.class);
        var verifier=new OrderRecipientVerifier(mock(OrderDetailFetcher.class),accountService,accounts);
        var account=new XianyuAccount();account.setId(1L);account.setStatus(1);when(accounts.selectById(1L)).thenReturn(account);
        var order=new XianyuGoodsOrder();order.setXianyuAccountId(1L);order.setBuyerUserId("buyer");
        var detail=new OrderDetailFetcher.OrderDetailInfo();detail.buyerUserId="buyer";
        when(accounts.countOwnBuyer("buyer")).thenReturn(1);
        assertThrows(IllegalStateException.class,()->verifier.verify(order,detail));
        when(accounts.countOwnBuyer("buyer")).thenReturn(0);detail.tradeStatus="REFUNDING";
        assertThrows(IllegalStateException.class,()->verifier.verify(order,detail));
    }
    @Test void ambiguousSkuLabelsDoNotGuessFirstVariant() {
        var resolver=new SkuResolver();var service=mock(GoodsSkuService.class);ReflectionTestUtils.setField(resolver,"goodsSkuService",service);
        var first=new XianyuGoodsSku();first.setSkuId("a");first.setValueText("红 XL");
        var second=new XianyuGoodsSku();second.setSkuId("b");second.setValueText("红/XL");
        when(service.listByXyGoodsId("g",1L)).thenReturn(List.of(first,second));
        assertNull(resolver.resolveSkuIdByText(1L,"g","红 XL"));
    }
    @Test void replayDoesNotCancelPendingAiReply() {
        var service=new AutoReplyDelayServiceImpl();var records=mock(XianyuGoodsAutoReplyRecordMapper.class);
        ReflectionTestUtils.setField(service,"autoReplyRecordMapper",records);
        ChatMessageData message=new ChatMessageData();message.setXianyuAccountId(1L);message.setSId("buyer@goofish");message.setPnmId("m");
        when(records.existsMessage(1L,"m")).thenReturn(1);service.submitDelayTask(message);
        verify(records,never()).cancelPendingBySession(any(),any());verify(records,never()).insert(any());
    }
    @Test void priceGuardRejectsBelowFloorAndFalsePriceChange() {
        assertNotEquals("最低 9 元",ReplyEnhancementService.guardPrice("最低 9 元",new BigDecimal("10"),true));
        assertEquals("最低 10 元",ReplyEnhancementService.guardPrice("最低 10 元",new BigDecimal("10"),true));
        assertNotEquals("已改价，请下单",ReplyEnhancementService.guardPrice("已改价，请下单",null,false));
    }
    @Test void notificationDistinguishesOrderDiscoveryFromFailure() {
        assertTrue(NotificationGuide.advice("ORDER_CREATED").contains("不代表发货失败"));
        assertTrue(NotificationGuide.advice("ACCOUNT_OFFLINE").contains("无需立即重新扫码"));
        assertTrue(NotificationGuide.advice("ACCOUNT_RECOVERED").contains("不会盲目重发"));
    }
}
