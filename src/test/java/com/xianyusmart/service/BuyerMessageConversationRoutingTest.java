package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import com.xianyusmart.service.delivery.OrderRecipientVerifier;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuyerMessageConversationRoutingTest {
    private static final String BUYER = "8000000000001";
    private static final String CID = "9000000000000000001";
    private static final String MESSAGE = "Original confirmed one-time card delivery content";
    private final WebSocketService socket = mock(WebSocketService.class);
    private final SentMessageSaveService sent = mock(SentMessageSaveService.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final XianyuChatMessageMapper chat = mock(XianyuChatMessageMapper.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final OrderDetailFetcher details = mock(OrderDetailFetcher.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrderRecipientVerifier verifier = new OrderRecipientVerifier(details, accounts);
    private final BuyerMessageService messages = new BuyerMessageService(socket, sent, orders, new ObjectMapper(), events);
    private final XianyuGoodsOrder persisted = new XianyuGoodsOrder();
    private final OrderDetailFetcher.OrderDetailInfo platform = new OrderDetailFetcher.OrderDetailInfo();

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        persisted.setId(233L);
        persisted.setTenantId(7L);
        persisted.setXianyuAccountId(1L);
        persisted.setOrderId("order-1");
        persisted.setXyGoodsId("goods-1");
        persisted.setBuyerUserId(BUYER);
        persisted.setBuyerUserName("synthetic buyer");
        persisted.setSid(BUYER + "@goofish"); // The API fallback must never be used as a conversation.
        persisted.setState(1);
        persisted.setConfirmState(1);
        persisted.setDeliveryStatus("COMPLETED");
        persisted.setDeliveryMessageState(0);
        persisted.setRefundStatus("NONE");
        persisted.setContent(MESSAGE);
        persisted.setDeliveryMessageContent(null); // Normal manual completion keeps original content only.
        platform.orderId = "order-1";
        platform.goodsId = "goods-1";
        platform.buyerUserId = BUYER;
        platform.tradeStatus = "SHIPPED";
        platform.consignTime = "2026-10-09 14:34:35";
        XianyuAccount account = new XianyuAccount();
        account.setId(1L);
        account.setTenantId(7L);
        account.setStatus(1);
        when(accounts.selectById(1L)).thenReturn(account);
        when(orders.selectByAccountIdAndOrderId(1L, "order-1")).thenAnswer(call -> snapshot());
        when(orders.selectById(233L)).thenAnswer(call -> snapshot());
        when(details.fetch(1L, "goods-1", "order-1")).thenReturn(platform);
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER))
                .thenReturn(List.of(CID + "@goofish"));
        when(socket.isConnected(1L)).thenReturn(true);
        when(socket.sendMessageWithResult(1L, CID, BUYER, MESSAGE)).thenReturn(true);
        ReflectionTestUtils.setField(verifier, "orders", orders);
        ReflectionTestUtils.setField(messages, "recipientVerifier", verifier);
        ReflectionTestUtils.setField(messages, "chatMessages", chat);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void confirmedCardResendUsesUniqueActualConversationAndSameOriginalContent() {
        assertTrue(messages.resendDeliveryMessage(1L, "order-1"));
        var sequence = inOrder(details, chat, socket, sent);
        sequence.verify(details).fetch(1L, "goods-1", "order-1");
        sequence.verify(chat).findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER);
        sequence.verify(socket).sendMessageWithResult(1L, CID, BUYER, MESSAGE);
        sequence.verify(sent).saveAiAssistantReply(1L, CID, BUYER, MESSAGE, "goods-1");
        verify(socket, never()).sendMessageWithResult(1L, BUYER, BUYER, MESSAGE);
        verify(orders).selectByAccountIdAndOrderId(1L, "order-1");
        verify(orders, times(2)).selectById(233L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(events);
        assertEquals(MESSAGE, persisted.getContent());
        assertEquals(1, persisted.getConfirmState());
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
    }

    @Test
    void noInboundConversationDoesNotUseValidLookingPersistedSidFallback() {
        persisted.setSid(CID + "@goofish");
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER)).thenReturn(List.of());
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        assertNothingSent();
    }

    @Test
    void multipleDistinctInboundConversationsCannotBeGuessedFromPersistedSid() {
        persisted.setSid(CID + "@goofish");
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER))
                .thenReturn(List.of(CID + "@goofish", "9000000000000000002@goofish"));
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        assertNothingSent();
    }

    @Test
    void unavailableConversationQueryResultCannotSend() {
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER)).thenReturn(null);
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "9000000000000000001", "CID@goofish", "9000000000000000001@other",
            "9000000000000000001@goofish-extra", "8000000000001@goofish"})
    void malformedSessionOrBuyerIdConversationNeverSends(String session) {
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER)).thenReturn(List.of(session));
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        assertNothingSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "goods", "buyer", "trade-status"})
    void platformIdentityOrStatusFailureStopsBeforeConversationLookup(String failure) {
        switch (failure) {
            case "order" -> platform.orderId = "other-order";
            case "goods" -> platform.goodsId = "other-goods";
            case "buyer" -> platform.buyerUserId = "other-buyer";
            case "trade-status" -> platform.tradeStatus = "REFUNDED";
        }
        assertThrows(IllegalStateException.class, () -> messages.resendDeliveryMessage(1L, "order-1"));
        verifyNoInteractions(chat, sent);
        assertNothingSent();
    }

    @Test
    void alteredContentCannotUseVerifiedConversationToBypassOriginalBodyGuard() {
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(messages, "sendDeliveryMessage", snapshot(), "altered card content"));
        verifyNoInteractions(chat, socket, sent);
    }

    @Test
    void changedPersistedTenantRejectsBeforeRouteLookup() {
        XianyuGoodsOrder requested = snapshot();
        persisted.setTenantId(8L);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(messages, "sendDeliveryMessage", requested, MESSAGE));
        verifyNoInteractions(chat, socket, sent);
    }

    @Test
    void missingPersistedTenantCannotQueryOrSendWithoutScope() {
        persisted.setTenantId(null);
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        verifyNoInteractions(chat, sent);
        assertNothingSent();
    }

    @Test
    void routeQueryNeverUsesAnotherTenantAccountGoodsOrBuyerEvidence() {
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER)).thenReturn(List.of());
        when(chat.findVerifiedBuyerInboundSessions(8L, 1L, "goods-1", BUYER)).thenReturn(List.of(CID + "@goofish"));
        when(chat.findVerifiedBuyerInboundSessions(7L, 2L, "goods-1", BUYER)).thenReturn(List.of(CID + "@goofish"));
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "other-goods", BUYER)).thenReturn(List.of(CID + "@goofish"));
        when(chat.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", "other-buyer")).thenReturn(List.of(CID + "@goofish"));
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        verify(chat).findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", BUYER);
        verifyNoMoreInteractions(chat);
        assertNothingSent();
    }

    @Test
    void wrongCurrentTenantCannotReadRouteOrSendDespiteMatchingRequestedOrder() {
        TenantContext.set(8L);
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        verifyNoInteractions(chat, sent);
        assertNothingSent();
    }

    @Test
    void missingCurrentTenantCannotReadRouteOrSendDespiteMatchingRequestedOrder() {
        TenantContext.clear();
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        verifyNoInteractions(chat, sent);
        assertNothingSent();
    }

    @Test
    void explicitSendFailureDoesNotCreateSentRecord() {
        when(socket.sendMessageWithResult(1L, CID, BUYER, MESSAGE)).thenReturn(false);
        assertFalse(messages.resendDeliveryMessage(1L, "order-1"));
        verify(socket).sendMessageWithResult(1L, CID, BUYER, MESSAGE);
        verifyNoInteractions(sent, events);
    }

    @Test
    void receiptInvitationUsesSameVerifiedConversationWithCompletedReceiptGate() {
        platform.tradeStatus = "COMPLETED";
        when(socket.sendMessageWithResult(1L, CID, BUYER, "Please review order-1")).thenReturn(true);
        assertTrue(messages.sendReceiptFollowUp(snapshot(), "Please review {orderId}"));
        verify(socket).sendMessageWithResult(1L, CID, BUYER, "Please review order-1");
        verify(sent).saveAiAssistantReply(1L, CID, BUYER, "Please review order-1", "goods-1");
    }

    @Test
    void mapperOnlyQueriesExactScopedActiveAccountInboundConversationIds() throws Exception {
        String sql = String.join(" ", XianyuChatMessageMapper.class
                .getMethod("findVerifiedBuyerInboundSessions", Long.class, Long.class, String.class, String.class)
                .getAnnotation(Select.class).value());
        assertTrue(sql.startsWith("SELECT DISTINCT message.s_id FROM xianyu_chat_message message "));
        assertTrue(sql.contains("JOIN xianyu_account account ON account.id=message.xianyu_account_id"));
        assertTrue(sql.contains("account.tenant_id=message.tenant_id"));
        assertTrue(sql.contains("message.tenant_id=#{tenantId}"));
        assertTrue(sql.contains("account.tenant_id=#{tenantId}"));
        assertTrue(sql.contains("account.id=#{accountId} AND account.status=1"));
        assertTrue(sql.contains("message.xianyu_account_id=#{accountId}"));
        assertTrue(sql.contains("message.xy_goods_id=#{goodsId}"));
        assertTrue(sql.contains("message.sender_user_id=#{buyerUserId}"));
        assertTrue(sql.contains("message.content_type=1"));
        assertTrue(sql.contains("message.s_id REGEXP '^[0-9]+@goofish$' LIMIT 2"));
        assertFalse(sql.contains("msg_content"));
        assertFalse(sql.contains("complete_msg"));
    }

    private void assertNothingSent() {
        verify(socket, never()).sendMessageWithResult(any(), any(), any(), any());
        verifyNoInteractions(sent);
    }

    private XianyuGoodsOrder snapshot() {
        XianyuGoodsOrder copy = new XianyuGoodsOrder();
        BeanUtils.copyProperties(persisted, copy);
        return copy;
    }
}
