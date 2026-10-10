package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundObservationServiceTest {
    JdbcTemplate jdbc; XianyuAccountMapper accounts; XianyuGoodsOrderMapper orders;
    KamiConfigService kami; NotificationCenterService notifications; RefundObservationService service;
    final List<String> writes = new ArrayList<>();
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class); accounts=mock(XianyuAccountMapper.class); orders=mock(XianyuGoodsOrderMapper.class);
        kami=mock(KamiConfigService.class); notifications=mock(NotificationCenterService.class);
        service=new RefundObservationService(jdbc,accounts,orders,kami,notifications,new ObjectMapper());
        XianyuAccount seller=new XianyuAccount(); seller.setId(1L); seller.setTenantId(7L); seller.setUnb("seller");
        XianyuAccount buyer=new XianyuAccount(); buyer.setId(2L); buyer.setTenantId(7L); buyer.setUnb("buyer");
        when(accounts.selectById(1L)).thenReturn(seller); when(accounts.selectById(2L)).thenReturn(buyer);
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0); Object[] arguments=(Object[])call.getRawArguments()[1];
            assertEquals(sql.chars().filter(c->c=='?').count(), arguments.length, "SQL bind count: "+sql);
            writes.add(sql); return 1;
        });
    }
    ChatMessageData message(long account, String sender) {
        ChatMessageData data=new ChatMessageData(); data.setXianyuAccountId(account); data.setSenderUserId(sender);
        data.setOrderId("order-1"); data.setPnmId("refund-card"); data.setSId("session"); return data;
    }
    void order(String buyer,String status) {
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of(
                "id",99L,"order_id","order-1","xy_goods_id","goods","buyer_user_id",buyer,"refund_status",status)));
    }
    @Test void outgoingBuyerCardCannotCreateMerchantCasesOrNotifications() {
        assertEquals("BUYER_EVENT_IGNORED", service.observeMessage(message(2,"buyer")));
        verifyNoInteractions(jdbc,orders,kami,notifications);
    }
    @Test void unknownOrderOnlyNotifiesUnverifiedAndDoesNotStopDelivery() {
        assertEquals("UNVERIFIED",service.observeMessage(message(1,"buyer")));
        assertTrue(writes.isEmpty()); verifyNoInteractions(orders,kami);
        verify(notifications).dispatch(eq("REFUND_REQUESTED"),eq(1L),anyString(),anyString(),argThat(data->
                !data.containsKey("orderId")&&Boolean.TRUE.equals(data.get("verificationRequired"))));
    }
    @Test void mismatchedBuyerDoesNotMutateExistingOrder() {
        order("actual-buyer","NONE"); assertEquals("UNVERIFIED",service.observeMessage(message(1,"buyer")));
        assertTrue(writes.isEmpty()); verifyNoInteractions(orders,kami);
    }
    @Test void verifiedSellerPersistsScopedCaseTimelineAuditAndCanonicalNotification() {
        order("buyer","NONE"); when(orders.skipPendingTaskForRefund(eq(1L),eq("order-1"),anyString())).thenReturn(1);
        assertEquals("RECORDED",service.observeMessage(message(1,"buyer")));
        assertEquals(4,writes.size()); verify(kami).releaseReservation("order-1",1L);
        assertTrue(writes.stream().anyMatch(sql->sql.contains("ON DUPLICATE KEY UPDATE")));
        assertTrue(writes.stream().anyMatch(sql->sql.contains("INSERT IGNORE INTO xianyu_order_event")));
        verify(notifications).dispatch(eq("REFUND_REQUESTED"),eq(1L),anyString(),anyString(),argThat(data->
                "refund-requested:1:order-1".equals(data.get("dedupeKey"))));
    }
    @Test void terminalRefundIsNotReopenedOrRenotifiedByDelayedCard() {
        order("buyer","REFUNDED"); assertEquals("RECORDED",service.observeMessage(message(1,"buyer")));
        verifyNoInteractions(notifications,kami);
    }
    @Test void ownBuyerStatusIgnoredAndUnrecognizedStatusIgnored() {
        assertEquals("BUYER_EVENT_IGNORED",service.observeStatus(2L,"order-1","buyer","REFUNDING"));
        assertEquals("IGNORED",service.observeStatus(1L,"order-1","buyer","PAID"));
        verifyNoInteractions(jdbc,orders,kami,notifications);
    }
}
