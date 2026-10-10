package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.*;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.*;
import com.xianyusmart.service.delivery.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDeliveryDualChannelMessageTest {
    private static final String ORIGINAL = "original prepared delivery";
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuChatMessageMapper chatMessages = mock(XianyuChatMessageMapper.class);
    private final OrderDetailFetcher fetcher = mock(OrderDetailFetcher.class);
    private final OrderService platform = mock(OrderService.class);
    private final WebSocketService socket = mock(WebSocketService.class);
    private final KamiConfigService kami = mock(KamiConfigService.class);
    private final DeliveryExecutionMapper executions = mock(DeliveryExecutionMapper.class);
    private final XianyuGoodsConfigMapper goodsConfigs = mock(XianyuGoodsConfigMapper.class);
    private final AccountAutomationPolicy policy = mock(AccountAutomationPolicy.class);
    private final DeliveryStrategyResolver content = mock(DeliveryStrategyResolver.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SentMessageSaveService sent = mock(SentMessageSaveService.class);
    private final BuyerMessageService messages = new BuyerMessageService(socket, sent, orders, new ObjectMapper(), events);
    private final OrderRecipientVerifier verifier = new OrderRecipientVerifier(fetcher, accounts);
    private final XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
    private final XianyuGoodsOrder persisted = new XianyuGoodsOrder();
    private final AtomicReference<OrderDetailFetcher.OrderDetailInfo> currentDetail = new AtomicReference<>();
    private final AutoDeliveryServiceImpl delivery = new AutoDeliveryServiceImpl() {
        @Override
        XianyuGoodsAutoDeliveryConfig resolveDeliveryConfig(Long accountId, String goodsId, String skuId, String skuName) {
            return config;
        }
    };

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        persisted.setId(10L);
        persisted.setTenantId(7L);
        persisted.setXianyuAccountId(1L);
        persisted.setOrderId("order-1");
        persisted.setXyGoodsId("goods-1");
        persisted.setBuyerUserId("buyer-1");
        persisted.setBuyerUserName("synthetic buyer");
        persisted.setSid("buyer-1@goofish");
        persisted.setConfirmState(0);
        persisted.setState(0);
        persisted.setDeliveryStatus("PROCESSING");
        persisted.setRefundStatus("NONE");
        persisted.setDeliveryMessageState(0);
        persisted.setDeliveryMessageAttemptCount(0);
        persisted.setBuyNum(1);
        persisted.setExpectedQuantity(1);
        persisted.setDeliveredQuantity(0);
        persisted.setExternalAttemptStarted(0);
        config.setDeliveryMode(1);
        config.setFixedTemplateId(20L);
        config.setVoucherDeliveryEnabled(1);
        config.setChatDeliveryEnabled(1);
        config.setDeliveryMessageTemplate("{deliveryContent}");
        XianyuAccount account = new XianyuAccount();
        account.setId(1L);
        account.setTenantId(7L);
        account.setStatus(1);
        when(accounts.selectById(1L)).thenReturn(account);
        when(orders.selectById(10L)).thenAnswer(call -> snapshot());
        when(orders.selectByAccountIdAndOrderId(1L, "order-1")).thenAnswer(call -> snapshot());
        when(fetcher.fetch(1L, "goods-1", "order-1")).thenAnswer(call -> currentDetail.get());
        currentDetail.set(detail("PAID"));
        when(socket.isConnected(1L)).thenReturn(true);
        when(chatMessages.findVerifiedBuyerInboundSessions(7L, 1L, "goods-1", "buyer-1"))
                .thenReturn(List.of("9000000000000000001@goofish"));
        when(socket.sendMessageWithResult(1L, "9000000000000000001", "buyer-1", ORIGINAL)).thenReturn(true);
        when(policy.guarded(anyLong(), any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        when(goodsConfigs.selectByAccountAndGoodsId(1L, "goods-1")).thenReturn(goodsConfig);
        when(content.resolve(anyInt(), any())).thenAnswer(call -> {
            DeliveryContext context = call.getArgument(1);
            XianyuFixedDeliveryTemplate template = new XianyuFixedDeliveryTemplate();
            template.setMessageTemplate("{deliveryContent}");
            context.setFixedTemplate(template);
            return ORIGINAL;
        });
        when(platform.consignDummyDelivery(1L, "order-1", ORIGINAL, List.of())).thenAnswer(call -> {
            currentDetail.set(detail("SHIPPED"));
            return OrderService.CONSIGN_SUCCESS;
        });
        when(orders.holdDeliveryMessage(eq(10L), anyString(), anyInt())).thenAnswer(call -> {
            persisted.setDeliveryMessageContent(call.getArgument(1));
            persisted.setDeliveryMessageState(call.getArgument(2));
            return 1;
        });
        when(orders.confirmFixedHeldDeliveryMessage(1L, "order-1")).thenAnswer(call -> {
            persisted.setConfirmState(1);
            persisted.setDeliveryMessageState(5);
            return 1;
        });
        when(orders.updateConfirmState(1L, "order-1")).thenAnswer(call -> {
            persisted.setConfirmState(1);
            return 1;
        });
        when(orders.activateDeliveryMessage(10L)).thenAnswer(call -> {
            persisted.setDeliveryMessageState(0);
            return 1;
        });
        when(orders.claimDeliveryMessage(10L)).thenAnswer(call -> {
            persisted.setDeliveryMessageState(2);
            return 1;
        });
        when(orders.markDeliveryMessageSent(10L)).thenAnswer(call -> {
            persisted.setDeliveryMessageState(1);
            return 1;
        });
        when(orders.deferDeliveryMessage(eq(10L), any())).thenAnswer(call -> {
            persisted.setDeliveryMessageState(0);
            persisted.setDeliveryMessageAttemptCount(persisted.getDeliveryMessageAttemptCount() + 1);
            return 1;
        });
        when(orders.finishAwaitingMessage(10L)).thenAnswer(call -> {
            if ("AWAITING_MESSAGE".equals(persisted.getDeliveryStatus()) && persisted.getDeliveryMessageState() == 1) {
                persisted.setDeliveryStatus("COMPLETED");
                persisted.setState(1);
            }
            return 1;
        });
        when(executions.finish(eq(10L), anyString(), anyInt(), anyString(), any(), any())).thenAnswer(call -> {
            persisted.setState(call.getArgument(2));
            persisted.setDeliveryStatus(call.getArgument(3));
            persisted.setContent(call.getArgument(4));
            persisted.setFailReason(call.getArgument(5));
            if ("COMPLETED".equals(persisted.getDeliveryStatus()) && Integer.valueOf(1).equals(persisted.getState())) {
                persisted.setDeliveredQuantity(persisted.getExpectedQuantity());
            }
            return 1;
        });
        when(executions.prepareCardQuantity(eq(10L), eq("synthetic-claim"), anyInt())).thenAnswer(call -> {
            int quantity = call.getArgument(2);
            if (!Integer.valueOf(0).equals(persisted.getExternalAttemptStarted())
                    || !Integer.valueOf(0).equals(persisted.getConfirmState())
                    || !Integer.valueOf(0).equals(persisted.getDeliveryMessageState())
                    || !Integer.valueOf(0).equals(persisted.getDeliveredQuantity()) || quantity <= 0) return 0;
            persisted.setBuyNum(quantity);
            persisted.setExpectedQuantity(quantity);
            return 1;
        });
        ReflectionTestUtils.setField(verifier, "orders", orders);
        ReflectionTestUtils.setField(messages, "recipientVerifier", verifier);
        ReflectionTestUtils.setField(messages, "chatMessages", chatMessages);
        ReflectionTestUtils.setField(delivery, "accountAutomationPolicy", policy);
        ReflectionTestUtils.setField(delivery, "goodsConfigMapper", goodsConfigs);
        ReflectionTestUtils.setField(delivery, "orderMapper", orders);
        ReflectionTestUtils.setField(delivery, "recipientVerifier", verifier);
        ReflectionTestUtils.setField(delivery, "orderDetailFetcher", fetcher);
        ReflectionTestUtils.setField(delivery, "deliveryStrategyResolver", content);
        ReflectionTestUtils.setField(delivery, "buyerMessageService", messages);
        ReflectionTestUtils.setField(delivery, "orderService", platform);
        ReflectionTestUtils.setField(delivery, "kamiConfigService", kami);
        ReflectionTestUtils.setField(delivery, "executionMapper", executions);
        ReflectionTestUtils.setField(delivery, "notificationCenterService", mock(NotificationCenterService.class));
        ReflectionTestUtils.setField(delivery, "emailNotifyService", mock(EmailNotifyService.class));
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void voucherOnceThenShippedPrivateMessageCompletesFixedAndCardDelivery(int mode) {
        config.setDeliveryMode(mode);
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        assertEquals(1, persisted.getConfirmState());
        assertEquals(1, persisted.getDeliveryMessageState());
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
        assertNull(persisted.getFailReason());
        verify(platform, times(1)).consignDummyDelivery(1L, "order-1", ORIGINAL, List.of());
        verify(socket, times(1)).sendMessageWithResult(1L, "9000000000000000001", "buyer-1", ORIGINAL);
        verify(fetcher, times(2)).fetch(1L, "goods-1", "order-1");
        verify(executions, never()).finish(any(), any(), anyInt(), eq("REVIEW_REQUIRED"), any(), any());
        if (mode == 2) verify(kami, times(1)).commitReservation("order-1", 1L, "goods-1", "buyer-1", "synthetic buyer");
    }

    @Test
    void initialShippedOrderCannotSubmitVoucherOrAllocateContent() {
        currentDetail.set(detail("SHIPPED"));
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verifyNoInteractions(platform, content, socket, kami);
        assertEquals("FAILED", persisted.getDeliveryStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity-missing", "quantity-zero", "quantity-negative", "order-missing", "goods-missing"})
    void untrustedCardDetailCannotAllocateOrSend(String failure) {
        config.setDeliveryMode(2);
        var detail = currentDetail.get();
        switch (failure) {
            case "quantity-missing" -> detail.buyNum = null;
            case "quantity-zero" -> detail.buyNum = 0;
            case "quantity-negative" -> detail.buyNum = -1;
            case "order-missing" -> detail.orderId = null;
            case "goods-missing" -> detail.goodsId = null;
        }
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verifyNoInteractions(platform, content, socket, kami);
        assertEquals("FAILED", persisted.getDeliveryStatus());
    }

    @Test
    void cardAllocationUsesOfficialPositiveQuantityExactly() {
        config.setDeliveryMode(2);
        currentDetail.get().buyNum = 3;
        persisted.setBuyNum(3);
        persisted.setExpectedQuantity(3);
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verify(content).resolve(eq(2), argThat(context -> context.getQuantity() == 3));
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
    }

    @Test
    void unstartedCardOrderPreparesOfficialQuantityBeforeAllocationAndRecordsSameQuantity() {
        config.setDeliveryMode(2);
        currentDetail.get().buyNum = 3;
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        var sequence = inOrder(executions, content, platform, kami);
        sequence.verify(executions).prepareCardQuantity(10L, "synthetic-claim", 3);
        sequence.verify(content).resolve(eq(2), argThat(context -> context.getQuantity() == 3));
        sequence.verify(platform).consignDummyDelivery(1L, "order-1", ORIGINAL, List.of());
        sequence.verify(kami).commitReservation("order-1", 1L, "goods-1", "buyer-1", "synthetic buyer");
        assertEquals(3, persisted.getExpectedQuantity());
        assertEquals(3, persisted.getBuyNum());
        assertEquals(3, persisted.getDeliveredQuantity());
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
    }

    @Test
    void disabledPaymentProjectionWithZeroExpectedQuantityCanPrepareOnlyBeforeFirstExternalAttempt() {
        config.setDeliveryMode(2);
        persisted.setExpectedQuantity(0);
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verify(executions).prepareCardQuantity(10L, "synthetic-claim", 1);
        assertEquals(1, persisted.getExpectedQuantity());
        assertEquals(1, persisted.getDeliveredQuantity());
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"external-started", "previous-delivery", "delivered-unknown", "confirm-unknown", "message-unknown"})
    void protectedOrUnknownQuantityPreparationCannotAllocate(String failure) {
        config.setDeliveryMode(2);
        persisted.setExpectedQuantity(0);
        switch (failure) {
            case "external-started" -> persisted.setExternalAttemptStarted(1);
            case "previous-delivery" -> persisted.setDeliveredQuantity(1);
            case "delivered-unknown" -> persisted.setDeliveredQuantity(null);
            case "confirm-unknown" -> persisted.setConfirmState(null);
            case "message-unknown" -> persisted.setDeliveryMessageState(null);
        }
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        assertEquals(0, persisted.getExpectedQuantity());
        verifyNoInteractions(platform, content, socket, kami);
        assertEquals("PROCESSING", persisted.getDeliveryStatus());
        verify(executions, never()).finish(any(), any(), anyInt(), anyString(), any(), any());
    }

    @Test
    void lostQuantityPreparationLeaseStopsBeforeCardAllocation() {
        config.setDeliveryMode(2);
        when(executions.prepareCardQuantity(eq(10L), anyString(), anyInt())).thenReturn(0);
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verifyNoInteractions(platform, content, socket, kami);
        assertEquals("PROCESSING", persisted.getDeliveryStatus());
        verify(executions, never()).finish(any(), any(), anyInt(), anyString(), any(), any());
    }

    @Test
    void rejectedQuantityCasDoesNotOverwriteConcurrentReviewProtection() {
        config.setDeliveryMode(2);
        when(executions.prepareCardQuantity(eq(10L), anyString(), anyInt())).thenAnswer(call -> {
            persisted.setDeliveryStatus("REVIEW_REQUIRED");
            persisted.setExternalAttemptStarted(1);
            persisted.setFailReason("concurrent review protection");
            return 0;
        });
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        assertEquals("REVIEW_REQUIRED", persisted.getDeliveryStatus());
        assertEquals("concurrent review protection", persisted.getFailReason());
        assertEquals(1, persisted.getExternalAttemptStarted());
        verifyNoInteractions(platform, content, socket, kami);
        verify(executions, never()).finish(any(), any(), anyInt(), anyString(), any(), any());
    }

    @Test
    void freshQuantityMismatchAfterPreparationStopsBeforeCardAllocation() {
        config.setDeliveryMode(2);
        when(executions.prepareCardQuantity(eq(10L), anyString(), anyInt())).thenAnswer(call -> {
            persisted.setExpectedQuantity(2);
            return 1;
        });
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verifyNoInteractions(platform, content, socket, kami);
        assertEquals("FAILED", persisted.getDeliveryStatus());
    }

    @Test
    void fixedTemplateStillAllowsMissingPurchaseQuantityWithOneContentFallback() {
        currentDetail.get().buyNum = null;
        try (DeliveryExecution ignored = new DeliveryExecution("synthetic-claim", () -> true, () -> true)) {
            delivery.executeDelivery(10L, 1L, "goods-1", "buyer-1@goofish", "order-1", "synthetic buyer", false);
        }
        verify(content).resolve(eq(1), argThat(context -> context.getQuantity() == 1));
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
    }

    @Test
    void retryUsesOriginalDatabaseMessageWithoutReconsigningOrAllocatingCards() {
        persisted.setConfirmState(1);
        persisted.setDeliveryStatus("AWAITING_MESSAGE");
        persisted.setDeliveryMessageContent(ORIGINAL);
        currentDetail.set(detail("SHIPPED"));
        when(orders.selectDueDeliveryMessages(100)).thenAnswer(call -> List.of(snapshot()));
        when(socket.sendMessageWithResult(1L, "9000000000000000001", "buyer-1", ORIGINAL)).thenReturn(false, true);

        messages.retryPendingDeliveryMessages();
        assertEquals(0, persisted.getDeliveryMessageState());
        messages.retryPendingDeliveryMessages();

        assertEquals(1, persisted.getDeliveryMessageState());
        assertEquals("COMPLETED", persisted.getDeliveryStatus());
        assertEquals(ORIGINAL, persisted.getDeliveryMessageContent());
        verify(socket, times(2)).sendMessageWithResult(1L, "9000000000000000001", "buyer-1", ORIGINAL);
        verify(orders, never()).prepareDeliveryMessage(anyLong(), anyString());
        verifyNoInteractions(platform, content, kami);
    }

    @Test
    void staleRetrySnapshotCannotAutomaticallySendOrActivateHistoricalReviewOrder() {
        persisted.setConfirmState(1);
        persisted.setDeliveryMessageContent(ORIGINAL);
        persisted.setDeliveryStatus("AWAITING_MESSAGE");
        XianyuGoodsOrder stale = snapshot();
        persisted.setDeliveryStatus("REVIEW_REQUIRED");
        currentDetail.set(detail("SHIPPED"));
        when(orders.selectDueDeliveryMessages(100)).thenReturn(List.of(stale));
        when(orders.selectCommittedHeldDeliveryMessages(100)).thenReturn(List.of(stale));

        messages.retryPendingDeliveryMessages();
        assertFalse(messages.activateAndSendDeliveryMessage(stale));
        assertFalse(messages.queueDeliveryMessage(stale, "replacement"));

        assertEquals("REVIEW_REQUIRED", persisted.getDeliveryStatus());
        assertEquals(ORIGINAL, persisted.getDeliveryMessageContent());
        verify(orders, never()).activateDeliveryMessage(anyLong());
        verify(orders, never()).claimDeliveryMessage(anyLong());
        verify(orders, never()).prepareDeliveryMessage(anyLong(), anyString());
        verify(orders, never()).deferDeliveryMessage(anyLong(), any());
        verifyNoInteractions(socket, fetcher, platform, content, kami);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "PROCESSING", "REFUNDED", "UNKNOWN", "", " "})
    void localRefundBlocksBeforeMessagePrepareActivateOrClaimDespiteShippedPlatform(String refundStatus) {
        persisted.setConfirmState(1);
        persisted.setDeliveryMessageContent(ORIGINAL);
        persisted.setDeliveryStatus("AWAITING_MESSAGE");
        XianyuGoodsOrder stale = snapshot();
        persisted.setRefundStatus(refundStatus);
        currentDetail.set(detail("SHIPPED"));
        when(orders.selectDueDeliveryMessages(100)).thenReturn(List.of(stale));
        when(orders.selectCommittedHeldDeliveryMessages(100)).thenReturn(List.of(stale));

        messages.retryPendingDeliveryMessages();
        assertFalse(messages.activateAndSendDeliveryMessage(stale));
        assertFalse(messages.queueDeliveryMessage(stale, "replacement"));
        assertFalse(messages.holdDeliveryMessage(stale, "replacement"));
        assertFalse(messages.holdFixedDeliveryMessage(stale, "replacement"));

        assertEquals(refundStatus, persisted.getRefundStatus());
        assertEquals("AWAITING_MESSAGE", persisted.getDeliveryStatus());
        assertEquals(ORIGINAL, persisted.getDeliveryMessageContent());
        assertEquals(0, persisted.getDeliveryMessageState());
        verify(orders, never()).activateDeliveryMessage(anyLong());
        verify(orders, never()).claimDeliveryMessage(anyLong());
        verify(orders, never()).prepareDeliveryMessage(anyLong(), anyString());
        verify(orders, never()).holdDeliveryMessage(anyLong(), anyString(), anyInt());
        verify(orders, never()).deferDeliveryMessage(anyLong(), any());
        verifyNoInteractions(socket, fetcher, platform, content, kami);
    }

    private XianyuGoodsOrder snapshot() {
        XianyuGoodsOrder copy = new XianyuGoodsOrder();
        BeanUtils.copyProperties(persisted, copy);
        return copy;
    }

    private static OrderDetailFetcher.OrderDetailInfo detail(String status) {
        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.orderId = "order-1";
        detail.goodsId = "goods-1";
        detail.buyerUserId = "buyer-1";
        detail.buyerUserName = "synthetic buyer";
        detail.buyNum = 1;
        detail.tradeStatus = status;
        detail.consignTime = "SHIPPED".equals(status) ? "2026-10-09 10:00:00" : null;
        return detail;
    }
}
