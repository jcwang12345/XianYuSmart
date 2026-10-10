package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderRecipientVerifierMessageTest {
    private final OrderDetailFetcher fetcher = mock(OrderDetailFetcher.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final OrderRecipientVerifier verifier = new OrderRecipientVerifier(fetcher, accounts);
    private final XianyuGoodsOrder persisted = new XianyuGoodsOrder();
    private final OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
    private final XianyuAccount account = new XianyuAccount();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(verifier, "orders", orders);
        persisted.setId(10L);
        persisted.setTenantId(7L);
        persisted.setXianyuAccountId(1L);
        persisted.setOrderId("order-1");
        persisted.setXyGoodsId("goods-1");
        persisted.setBuyerUserId("buyer-1");
        persisted.setDeliveryStatus("PROCESSING");
        persisted.setRefundStatus("NONE");
        persisted.setConfirmState(0);
        persisted.setState(0);
        persisted.setDeliveryMessageState(2);
        persisted.setDeliveryMessageContent("original delivery");
        account.setId(1L);
        account.setStatus(1);
        when(accounts.selectById(1L)).thenReturn(account);
        when(orders.selectById(10L)).thenReturn(persisted);
        detail.buyerUserId = "buyer-1";
        detail.orderId = "order-1";
        detail.goodsId = "goods-1";
        detail.tradeStatus = "PAID";
        when(fetcher.fetch(1L, "goods-1", "order-1")).thenReturn(detail);
    }

    @Test
    void paidChatOnlyDeliveryStillWorks() {
        assertEquals("buyer-1", verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED"})
    void confirmedVoucherAllowsOriginalMessageUsingFreshDatabaseEvidence(String status) {
        XianyuGoodsOrder stale = snapshot();
        persisted.setConfirmState(1);
        detail.tradeStatus = status;
        detail.consignTime = "2026-10-09 10:00:00";
        assertEquals(0, stale.getConfirmState());
        assertEquals("buyer-1", verifier.verifyDeliveryMessage(stale, "original delivery"));
        assertThrows(IllegalStateException.class, () -> verifier.verify(persisted, detail));
    }

    @Test
    void forgedInMemoryConfirmationCannotAuthorizeShippedMessage() {
        XianyuGoodsOrder forged = snapshot();
        forged.setConfirmState(1);
        detail.tradeStatus = "SHIPPED";
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(forged, "original delivery"));
    }

    @Test
    void confirmedCompletedChatCanResendOnlyItsOriginalContent() {
        persisted.setDeliveryStatus("COMPLETED");
        persisted.setState(1);
        persisted.setDeliveryMessageState(1);
        detail.tradeStatus = "COMPLETED";
        assertEquals("buyer-1", verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(snapshot(), "replacement content"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UNPAID", "REFUNDING", "REFUNDED", "CLOSED", "UNKNOWN"})
    void terminalOrUnknownPlatformStateCannotSendEvenWithConfirmedVoucher(String status) {
        persisted.setConfirmState(1);
        detail.tradeStatus = status;
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
    }

    @Test
    void paidStatusWithShippingTimeIsStillInconsistentAndRejected() {
        persisted.setConfirmState(1);
        detail.consignTime = "2026-10-09 10:00:00";
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "PROCESSING", "REFUNDED", "REJECTED", "CANCELLED", "UNKNOWN", "", " "})
    void localRefundStateBlocksOriginalMessageEvenWhenPlatformStillShipped(String refundStatus) {
        XianyuGoodsOrder stale = snapshot();
        persisted.setConfirmState(1);
        persisted.setRefundStatus(refundStatus);
        detail.tradeStatus = "SHIPPED";
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(stale, "original delivery"));
        verifyNoInteractions(fetcher);
    }

    @Test
    void legacyNullRefundStateRetainsNoneCompatibility() {
        persisted.setRefundStatus(null);
        persisted.setConfirmState(1);
        detail.tradeStatus = "SHIPPED";
        assertEquals("buyer-1", verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
    }

    @Test
    void messageRejectsWrongOrMissingPlatformIdentityAndDisabledAccount() {
        detail.buyerUserId = "other-buyer";
        assertRejected();
        detail.buyerUserId = "buyer-1";
        detail.orderId = "other-order";
        assertRejected();
        detail.orderId = null;
        assertRejected();
        detail.orderId = "order-1";
        detail.goodsId = "other-goods";
        assertRejected();
        detail.goodsId = null;
        assertRejected();
        detail.goodsId = "goods-1";
        account.setStatus(0);
        assertRejected();
    }

    @Test
    void requestedReferenceAndBuyerCannotReplacePersistedIdentity() {
        XianyuGoodsOrder wrongAccount = snapshot();
        wrongAccount.setXianyuAccountId(2L);
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(wrongAccount, "original delivery"));
        XianyuGoodsOrder wrongBuyer = snapshot();
        wrongBuyer.setBuyerUserId("other-buyer");
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(wrongBuyer, "original delivery"));
        verifyNoInteractions(fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEW_REQUIRED", "SKIPPED", "UNKNOWN"})
    void protectedLocalOrderCannotSendOrFetchPlatform(String status) {
        persisted.setDeliveryStatus(status);
        persisted.setConfirmState(1);
        detail.tradeStatus = "SHIPPED";
        assertRejected();
        verifyNoInteractions(fetcher);
    }

    private void assertRejected() {
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyDeliveryMessage(snapshot(), "original delivery"));
    }

    private XianyuGoodsOrder snapshot() {
        XianyuGoodsOrder copy = new XianyuGoodsOrder();
        BeanUtils.copyProperties(persisted, copy);
        return copy;
    }
}
