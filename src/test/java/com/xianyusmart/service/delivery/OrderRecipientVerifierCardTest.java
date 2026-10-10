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

class OrderRecipientVerifierCardTest {
    private final OrderDetailFetcher fetcher = mock(OrderDetailFetcher.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final OrderRecipientVerifier verifier = new OrderRecipientVerifier(fetcher, accounts);
    private final XianyuGoodsOrder persisted = new XianyuGoodsOrder();
    private final XianyuAccount account = new XianyuAccount();
    private final OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(verifier, "orders", orders);
        persisted.setId(10L);
        persisted.setTenantId(7L);
        persisted.setXianyuAccountId(1L);
        persisted.setOrderId("order-1");
        persisted.setXyGoodsId("goods-1");
        persisted.setBuyerUserId("buyer-1");
        persisted.setState(0);
        persisted.setDeliveryStatus("PROCESSING");
        persisted.setConfirmState(0);
        persisted.setDeliveryMessageState(0);
        persisted.setRefundStatus("NONE");
        persisted.setBuyNum(1);
        persisted.setExpectedQuantity(1);
        account.setId(1L);
        account.setTenantId(7L);
        account.setStatus(1);
        detail.orderId = "order-1";
        detail.goodsId = "goods-1";
        detail.buyerUserId = "buyer-1";
        detail.tradeStatus = "PAID";
        detail.buyNum = 1;
        when(orders.selectById(10L)).thenReturn(persisted);
        when(accounts.selectById(1L)).thenReturn(account);
    }

    @Test
    void freshPersistedIdentityAndExplicitPaidQuantityAuthorizeCardOrder() {
        detail.buyNum = 3;
        persisted.setBuyNum(3);
        persisted.setExpectedQuantity(3);
        assertEquals("buyer-1", verifier.verifyCardOrder(snapshot(), detail));
        verify(orders).selectById(10L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(fetcher);
    }

    @Test
    void staleSafeStateCannotReplaceFreshRefundObservation() {
        XianyuGoodsOrder stale = snapshot();
        persisted.setRefundStatus("REQUESTED");
        assertRejected(stale);
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "tenant", "account", "order", "goods", "buyer"})
    void requestedIdentityMustMatchPersistedOrder(String field) {
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
    void missingPersistedIdentityCannotAuthorizeCards(String field) {
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
    void absentLocalOrderCannotAuthorizeCards() {
        XianyuGoodsOrder requested = snapshot();
        when(orders.selectById(10L)).thenReturn(null);
        assertRejected(requested);
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void absentRequestedOrderCannotAuthorizeCards() {
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyCardOrder(null, detail));
        XianyuGoodsOrder requested = snapshot();
        requested.setId(null);
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyCardOrder(requested, detail));
        verifyNoInteractions(orders, accounts, fetcher);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING", "COMPLETED", "AWAITING_MESSAGE", "FAILED", "REVIEW_REQUIRED", "UNKNOWN", ""})
    void onlyClaimedProcessingOrderCanAllocateCards(String status) {
        persisted.setDeliveryStatus(status);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void completedLocalOrderCannotAllocateAgain() {
        persisted.setState(1);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @Test
    void confirmedVoucherCannotAllocateAgain() {
        persisted.setConfirmState(1);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void existingMessageOutcomeCannotAllocateAgain(int state) {
        persisted.setDeliveryMessageState(state);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "PROCESSING", "REFUNDED", "REJECTED", "CANCELLED", "UNKNOWN", "", " "})
    void localRefundOrUnknownObservationBlocksCards(String refund) {
        persisted.setRefundStatus(refund);
        assertRejected(snapshot());
        verifyNoInteractions(accounts, fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "disabled", "wrong-account", "wrong-tenant", "missing-tenant"})
    void accountMustBeActiveAndMatchPersistedTenant(String failure) {
        switch (failure) {
            case "absent" -> when(accounts.selectById(1L)).thenReturn(null);
            case "disabled" -> account.setStatus(0);
            case "wrong-account" -> account.setId(2L);
            case "wrong-tenant" -> account.setTenantId(8L);
            case "missing-tenant" -> account.setTenantId(null);
        }
        assertRejected(snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "goods", "buyer"})
    void platformIdentityMustBePresentAndExact(String field) {
        for (String value : new String[]{null, "", " ", "other"}) {
            switch (field) {
                case "order" -> detail.orderId = value;
                case "goods" -> detail.goodsId = value;
                case "buyer" -> detail.buyerUserId = value;
            }
            assertRejected(snapshot());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UNPAID", "SHIPPED", "COMPLETED", "CLOSED", "REFUNDING", "REFUNDED", "UNKNOWN", "", " "})
    void onlyExplicitPaidPlatformStatusAuthorizesCards(String status) {
        detail.tradeStatus = status;
        assertRejected(snapshot());
    }

    @Test
    void consignTimeBlocksEvenPaidPlatformOrder() {
        detail.consignTime = "2026-10-09 10:00:00";
        assertRejected(snapshot());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, Integer.MIN_VALUE})
    void missingOrNonpositiveOfficialQuantityCannotUseLocalFallback(Integer quantity) {
        persisted.setBuyNum(5);
        persisted.setExpectedQuantity(5);
        detail.buyNum = quantity;
        assertRejected(snapshot());
    }

    @Test
    void missingPlatformDetailCannotAuthorizeCards() {
        assertThrows(IllegalStateException.class, () -> verifier.verifyCardOrder(snapshot(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyNum-missing", "expected-missing", "buyNum-mismatch", "expected-mismatch"})
    void localQuantityMustBePresentAndMatchOfficialQuantity(String failure) {
        switch (failure) {
            case "buyNum-missing" -> persisted.setBuyNum(null);
            case "expected-missing" -> persisted.setExpectedQuantity(null);
            case "buyNum-mismatch" -> persisted.setBuyNum(3);
            case "expected-mismatch" -> persisted.setExpectedQuantity(3);
        }
        assertRejected(snapshot());
    }

    @Test
    void refreshedLocalBuyNumCannotHideOriginalMissingQuantity() {
        persisted.setBuyNum(null);
        XianyuGoodsOrder requested = snapshot();
        persisted.setBuyNum(1);
        assertRejected(requested);
    }

    @Test
    void preflightCanAuthorizePreparationButNotAllocationForDisabledZeroQuantityProjection() {
        persisted.setExpectedQuantity(0);
        persisted.setBuyNum(0);
        assertEquals("buyer-1", verifier.verifyCardOrderBeforeQuantityPreparation(snapshot(), detail));
        assertRejected(snapshot());
        verifyNoInteractions(fetcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity-missing", "quantity-zero", "quantity-negative", "order-missing",
            "goods-missing", "buyer-mismatch", "not-paid", "already-shipped", "local-refund"})
    void preflightNeverAuthorizesPreparationWithoutExactPaidIdentityAndPositiveQuantity(String failure) {
        persisted.setExpectedQuantity(0);
        switch (failure) {
            case "quantity-missing" -> detail.buyNum = null;
            case "quantity-zero" -> detail.buyNum = 0;
            case "quantity-negative" -> detail.buyNum = -1;
            case "order-missing" -> detail.orderId = null;
            case "goods-missing" -> detail.goodsId = null;
            case "buyer-mismatch" -> detail.buyerUserId = "other";
            case "not-paid" -> detail.tradeStatus = "SHIPPED";
            case "already-shipped" -> detail.consignTime = "2026-10-09 10:00:00";
            case "local-refund" -> persisted.setRefundStatus("REQUESTED");
        }
        assertThrows(IllegalStateException.class,
                () -> verifier.verifyCardOrderBeforeQuantityPreparation(snapshot(), detail));
    }

    @Test
    void staleMatchingQuantityCannotReplaceChangedPersistedQuantity() {
        XianyuGoodsOrder requested = snapshot();
        persisted.setExpectedQuantity(2);
        assertRejected(requested);
    }

    @Test
    void fixedTemplateVerifierRetainsExistingCompatibilityWithoutQuantityOrOptionalIdentity() {
        detail.buyNum = null;
        detail.goodsId = null;
        detail.orderId = null;
        assertEquals("buyer-1", verifier.verify(persisted, detail));
        assertRejected(snapshot());
    }

    private void assertRejected(XianyuGoodsOrder requested) {
        assertThrows(IllegalStateException.class, () -> verifier.verifyCardOrder(requested, detail));
    }

    private XianyuGoodsOrder snapshot() {
        XianyuGoodsOrder copy = new XianyuGoodsOrder();
        BeanUtils.copyProperties(persisted, copy);
        return copy;
    }
}
