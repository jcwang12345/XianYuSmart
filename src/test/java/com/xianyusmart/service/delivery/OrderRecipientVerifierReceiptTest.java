package com.xianyusmart.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.SentMessageSaveService;
import com.xianyusmart.service.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderRecipientVerifierReceiptTest {
    private final OrderDetailFetcher fetcher = mock(OrderDetailFetcher.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final XianyuChatMessageMapper chatMessages = mock(XianyuChatMessageMapper.class);
    private final OrderRecipientVerifier verifier = new OrderRecipientVerifier(fetcher, accounts);
    private final WebSocketService socket = mock(WebSocketService.class);
    private final SentMessageSaveService sent = mock(SentMessageSaveService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BuyerMessageService messages = new BuyerMessageService(
            socket, sent, orders, new ObjectMapper(), events);
    private final XianyuGoodsOrder persisted = new XianyuGoodsOrder();
    private final OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
    private final XianyuAccount account = new XianyuAccount();

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        ReflectionTestUtils.setField(verifier, "orders", orders);
        ReflectionTestUtils.setField(messages, "recipientVerifier", verifier);
        ReflectionTestUtils.setField(messages, "chatMessages", chatMessages);
        persisted.setId(10L);
        persisted.setTenantId(7L);
        persisted.setXianyuAccountId(1L);
        persisted.setOrderId("order-1");
        persisted.setXyGoodsId("goods-1");
        persisted.setBuyerUserId("buyer-1");
        persisted.setBuyerUserName("Buyer");
        persisted.setDeliveryStatus("COMPLETED");
        persisted.setRefundStatus("NONE");
        persisted.setConfirmState(1);
        persisted.setState(1);
        persisted.setDeliveryMessageState(0);
        persisted.setContent("original delivery");
        persisted.setDeliveryMessageContent("original delivery");
        // Legacy rows need no fabricated delivered_quantity to prove their confirmed delivery.
        persisted.setDeliveredQuantity(null);
        account.setId(1L);
        account.setTenantId(7L);
        account.setStatus(1);
        when(accounts.selectById(1L)).thenReturn(account);
        when(orders.selectById(10L)).thenReturn(persisted);
        detail.buyerUserId = "buyer-1";
        detail.orderId = "order-1";
        detail.goodsId = "goods-1";
        detail.tradeStatus = "COMPLETED";
        when(fetcher.fetch(1L, "goods-1", "order-1")).thenReturn(detail);
        when(chatMessages.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", "buyer-1"))
                .thenReturn(List.of("9000000000000000001@goofish"));
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void completedReceiptUsesFreshPersistedDeliveryEvidence() {
        XianyuGoodsOrder stale = snapshot();
        stale.setState(0);
        stale.setConfirmState(0);
        stale.setDeliveryStatus("PENDING");
        assertEquals("buyer-1", verifier.verifyReceiptFollowUp(stale));
    }

    @Test
    void confirmedChatDeliveryCanInviteWithoutShipmentConfirmation() {
        persisted.setConfirmState(0);
        persisted.setDeliveryMessageState(1);
        assertEquals("buyer-1", verifier.verifyReceiptFollowUp(snapshot()));
    }

    @Test
    void forgedCompletionCannotReplaceFreshPersistedEvidence() {
        XianyuGoodsOrder forged = snapshot();
        persisted.setState(0);
        persisted.setConfirmState(0);
        persisted.setDeliveryMessageState(0);
        persisted.setDeliveryStatus("PENDING");
        assertRejected(forged);
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void completedLocalStateAloneCannotAuthorizeReceiptFollowUp() {
        persisted.setConfirmState(0);
        persisted.setDeliveryMessageState(0);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 2})
    void incompleteLocalStateCannotAuthorizeReceiptFollowUp(Integer state) {
        persisted.setState(state);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING", "PROCESSING", "RETRY_WAIT", "AWAITING_MESSAGE", "FAILED",
            "REVIEW_REQUIRED", "SKIPPED", "UNKNOWN", "", " "})
    void protectedOrIncompleteLocalDeliveryCannotFetchPlatform(String status) {
        persisted.setDeliveryStatus(status);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "PROCESSING", "REFUNDED", "REJECTED", "CANCELLED", "UNKNOWN", "", " "})
    void refundObservationBlocksReceiptFollowUp(String refundStatus) {
        XianyuGoodsOrder stale = snapshot();
        persisted.setRefundStatus(refundStatus);
        assertRejected(stale);
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void legacyNullRefundRetainsNoneContract() {
        persisted.setRefundStatus(null);
        assertEquals("buyer-1", verifier.verifyReceiptFollowUp(snapshot()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "tenant", "account", "order", "goods", "buyer"})
    void requestedIdentityCannotReplacePersistedIdentity(String field) {
        XianyuGoodsOrder requested = snapshot();
        switch (field) {
            case "id" -> requested.setId(11L);
            case "tenant" -> requested.setTenantId(8L);
            case "account" -> requested.setXianyuAccountId(2L);
            case "order" -> requested.setOrderId("other-order");
            case "goods" -> requested.setXyGoodsId("other-goods");
            case "buyer" -> requested.setBuyerUserId("other-buyer");
        }
        assertRejected(requested);
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "account", "order", "goods", "buyer"})
    void missingPersistedIdentityCannotBeAcceptedEvenWhenRequestMatches(String field) {
        switch (field) {
            case "tenant" -> persisted.setTenantId(null);
            case "account" -> persisted.setXianyuAccountId(null);
            case "order" -> persisted.setOrderId(null);
            case "goods" -> persisted.setXyGoodsId("");
            case "buyer" -> persisted.setBuyerUserId(" ");
        }
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void missingPersistedOrderCannotAuthorizeReceiptFollowUp() {
        when(orders.selectById(10L)).thenReturn(null);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "wrong-account", "wrong-tenant", "missing-tenant"})
    void accountMustBeNormalAndMatchTenantBeforePlatformFetch(String failure) {
        switch (failure) {
            case "missing" -> when(accounts.selectById(1L)).thenReturn(null);
            case "disabled" -> account.setStatus(0);
            case "wrong-account" -> account.setId(2L);
            case "wrong-tenant" -> account.setTenantId(8L);
            case "missing-tenant" -> account.setTenantId(null);
        }
        assertRejected(snapshot());
        verifyNoInteractions(fetcher);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PAID", "SHIPPED", "UNPAID", "CLOSED", "REFUNDING", "REFUNDED", "UNKNOWN", "", " "})
    void onlyExplicitPlatformCompletedReceiptCanAuthorizeFollowUp(String status) {
        detail.tradeStatus = status;
        assertRejected(snapshot());
    }

    @Test
    void absentPlatformDetailCannotAuthorizeReceiptFollowUp() {
        when(fetcher.fetch(1L, "goods-1", "order-1")).thenReturn(null);
        assertRejected(snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "goods", "buyer"})
    void platformIdentityMustBeExactAndPresent(String field) {
        setDetailIdentity(field, "other");
        assertRejected(snapshot());
        setDetailIdentity(field, null);
        assertRejected(snapshot());
        setDetailIdentity(field, " ");
        assertRejected(snapshot());
    }

    @Test
    void receiptPrecheckDoesNotClaimOrSendAnything() {
        messages.verifyReceiptFollowUp(snapshot());
        verify(orders).selectById(10L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(socket, sent, events);
    }

    @Test
    void invitationIsIndependentOfDeliveryContentButFirstDeliveryRulesRemainStrict() {
        when(socket.isConnected(1L)).thenReturn(true);
        when(socket.sendMessageWithResult(1L, "9000000000000000001", "buyer-1", "Thanks Buyer, please review order-1"))
                .thenReturn(true);
        assertTrue(messages.sendReceiptFollowUp(snapshot(), "Thanks {buyerName}, please review {orderId}"));
        verify(socket).sendMessageWithResult(1L, "9000000000000000001", "buyer-1", "Thanks Buyer, please review order-1");
        verify(sent).saveAiAssistantReply(1L, "9000000000000000001", "buyer-1", "Thanks Buyer, please review order-1", "goods-1");
        assertThrows(IllegalStateException.class, () -> verifier.verify(persisted, detail));
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(snapshot(), "Thanks Buyer, please review order-1"));
    }

    @Test
    void sendingRechecksPlatformAfterSuccessfulPrecheck() {
        XianyuGoodsOrder requested = snapshot();
        messages.verifyReceiptFollowUp(requested);
        detail.tradeStatus = "SHIPPED";
        assertThrows(IllegalStateException.class, () -> messages.sendReceiptFollowUp(requested, "Please review"));
        verify(fetcher, times(2)).fetch(1L, "goods-1", "order-1");
        verifyNoInteractions(socket, sent, events);
    }

    @Test
    void sendingRechecksFreshLocalRefundAfterSuccessfulPrecheck() {
        XianyuGoodsOrder requested = snapshot();
        messages.verifyReceiptFollowUp(requested);
        persisted.setRefundStatus("REQUESTED");
        assertThrows(IllegalStateException.class, () -> messages.sendReceiptFollowUp(requested, "Please review"));
        verify(orders, times(2)).selectById(10L);
        verify(fetcher).fetch(1L, "goods-1", "order-1");
        verifyNoInteractions(socket, sent, events);
    }

    private void setDetailIdentity(String field, String value) {
        switch (field) {
            case "order" -> detail.orderId = value;
            case "goods" -> detail.goodsId = value;
            case "buyer" -> detail.buyerUserId = value;
        }
    }

    private void assertRejected(XianyuGoodsOrder requested) {
        assertThrows(IllegalStateException.class, () -> verifier.verifyReceiptFollowUp(requested));
    }

    private XianyuGoodsOrder snapshot() {
        XianyuGoodsOrder copy = new XianyuGoodsOrder();
        BeanUtils.copyProperties(persisted, copy);
        return copy;
    }
}
