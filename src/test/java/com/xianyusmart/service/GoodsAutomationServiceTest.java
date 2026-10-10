package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.exception.DeliveryUncertainException;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.delivery.DeliveryExecution;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoodsAutomationServiceTest {
    private static final long TENANT = 2000L;
    private static final long ACCOUNT = 1L;
    private static final String GOODS = "UNIT-GOODS";
    private static final String ORDER = "UNIT-NEW-240";
    private static final String INVITE = "欢迎按真实体验分享评价。";
    private static final String LIST_API = "mtop.taobao.idle.merchant.rate.list";
    private static final String RATE_API = "mtop.taobao.idle.merchant.rate.create";

    private final XianyuGoodsConfigMapper switches = mock(XianyuGoodsConfigMapper.class);
    private final XianyuGoodsOrderMapper orders = mock(XianyuGoodsOrderMapper.class);
    private final AccountService accounts = mock(AccountService.class);
    private final XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
    private final OperationLogService logs = mock(OperationLogService.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final BuyerMessageService messages = mock(BuyerMessageService.class);
    private final RatingContentService ratingContent = new RatingContentService(new ObjectMapper());
    private final RiskControlService risk = mock(RiskControlService.class);
    private final OrderEngagementService engagement = mock(OrderEngagementService.class);
    private final OperationalIssueService issues = mock(OperationalIssueService.class);
    private final AutomationActivationService activation = mock(AutomationActivationService.class);
    private GoodsAutomationService service;
    private XianyuGoodsOrder order;
    private XianyuGoodsConfig goodsConfig;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        service = new GoodsAutomationService(switches, orders, accounts, api, logs, configs, messages,
                ratingContent, risk, engagement, issues,
                Clock.fixed(Instant.parse("2026-10-09T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        ReflectionTestUtils.setField(service, "activationService", activation);
        order = safeOrder();
        goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setTenantId(TENANT);
        goodsConfig.setXianyuAccountId(ACCOUNT);
        goodsConfig.setXyGoodsId(GOODS);
        goodsConfig.setXianyuAutoRateOn(XianyuGoodsConfig.AUTO_RATE_ALWAYS);
        goodsConfig.setXianyuAutoRateContent("感谢支持，交易愉快。");
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setReceiptFollowUpMessages("[\"" + INVITE + "\"]");
        config.setReceiptFollowUpIntervalSeconds(5);
        when(activation.allowsOrder(anyString(), any())).thenReturn(true);
        when(accounts.getCookieByAccountId(ACCOUNT)).thenReturn("unit-test-cookie");
        when(switches.selectAutoRateEnabled()).thenReturn(List.of(goodsConfig));
        when(orders.selectByAccountIdAndOrderId(ACCOUNT, ORDER)).thenAnswer(invocation -> order);
        when(orders.selectDueReceiptFollowUps(100)).thenAnswer(invocation -> List.of(order));
        when(orders.claimReceiptFollowUp(anyLong(), anyInt())).thenReturn(1);
        when(configs.findByAccountIdAndGoodsIdNoSku(ACCOUNT, GOODS)).thenReturn(config);
        when(messages.parseReceiptFollowUpMessages(config.getReceiptFollowUpMessages())).thenReturn(List.of(INVITE));
        when(messages.normalizeReceiptFollowUpInterval(5)).thenReturn(5);
        when(messages.sendReceiptFollowUp(any(), eq(INVITE))).thenAnswer(invocation -> {
            DeliveryExecution.beforeExternal();
            return true;
        });
        when(engagement.beginInvite(eq(TENANT), eq(ACCOUNT), anyLong(), eq(ORDER), anyInt(), eq(INVITE)))
                .thenReturn(admission(OrderEngagementService.Decision.SEND));
        when(engagement.beginAutoRate(eq(TENANT), eq(ACCOUNT), anyLong(), eq(ORDER), anyString()))
                .thenReturn(admission(OrderEngagementService.Decision.SEND));
        when(risk.tryAcquire(ACCOUNT, RiskControlService.WriteOperation.ORDER_RATE))
                .thenReturn(new RiskControlService.GuardDecision(true, RiskControlService.GuardState.NORMAL,
                        0, 0, null, RiskControlService.WriteOperation.ORDER_RATE));
        stubPlatform("交易成功", "5", "5");
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("success", true, "successOrderIds", List.of(ORDER))));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        assertFalse(DeliveryExecution.started());
    }

    @Test
    void confirmedNewOrderReceivesOneInviteAfterPreverification() {
        service.runReceiptFollowUps();
        var sequence = inOrder(messages, engagement);
        sequence.verify(messages).verifyReceiptFollowUp(order);
        sequence.verify(engagement).beginInvite(TENANT, ACCOUNT, 240L, ORDER, 0, INVITE);
        sequence.verify(messages).sendReceiptFollowUp(order, INVITE);
        sequence.verify(engagement).success(TENANT, 17L);
        verify(orders).markBuyerConfirmedReceipt(240L);
        verify(orders).updateReceiptFollowUpProgress(240L, 1, 1, null, 0);
        verify(activation).initializeEnabled();
    }

    @ParameterizedTest
    @ValueSource(strings = {"已发货", "退款中", "交易关闭", ""})
    void platformMustConfirmReceiptBeforeInviting(String tradeStatus) throws JsonProcessingException {
        stubPlatform(tradeStatus, "5", "5");
        service.runReceiptFollowUps();
        verify(messages, never()).sendReceiptFollowUp(any(), anyString());
        verifyNoInteractions(engagement);
        verify(orders).deferReceiptFollowUp(eq(240L), any());
    }

    @ParameterizedTest
    @MethodSource("unsafeOrders")
    void unsafeLocalOrderNeverClaimsOrChecksPlatformForInvite(XianyuGoodsOrder unsafe) {
        order = unsafe;
        service.runReceiptFollowUps();
        verify(orders, never()).claimReceiptFollowUp(anyLong(), anyInt());
        verifyNoInteractions(api, messages, engagement);
    }

    @Test
    void activationBoundaryExcludesExistingOrdersWithoutClaiming() {
        when(activation.allowsOrder("REVIEW_INVITE", order)).thenReturn(false);
        service.runReceiptFollowUps();
        verify(orders, never()).claimReceiptFollowUp(anyLong(), anyInt());
        verifyNoInteractions(api, messages, engagement);
    }

    @Test
    void buyerAlreadyRatedCompletesInviteWithoutSending() throws JsonProcessingException {
        stubPlatform("交易成功", "5", "4");
        service.runReceiptFollowUps();
        verify(orders).updateReceiptFollowUpProgress(240L, 0, 1, null, 0);
        verifyNoInteractions(engagement, messages);
    }

    @Test
    void confirmedInviteEventAdvancesProgressWithoutRepeatingMessage() {
        when(engagement.beginInvite(TENANT, ACCOUNT, 240L, ORDER, 0, INVITE))
                .thenReturn(admission(OrderEngagementService.Decision.ALREADY_CONFIRMED));
        service.runReceiptFollowUps();
        verify(messages, never()).sendReceiptFollowUp(any(), anyString());
        verify(orders).updateReceiptFollowUpProgress(240L, 1, 1, null, 0);
    }

    @Test
    void unknownInviteEventStopsWithoutResending() {
        when(engagement.beginInvite(TENANT, ACCOUNT, 240L, ORDER, 0, INVITE))
                .thenReturn(admission(OrderEngagementService.Decision.RESULT_UNKNOWN));
        service.runReceiptFollowUps();
        verify(messages, never()).sendReceiptFollowUp(any(), anyString());
        verify(orders).updateReceiptFollowUpProgress(240L, 0, 1, null, 0);
        verify(issues).report(eq("REVIEW_INVITE"), eq("review-invite:240:0"), eq(ACCOUNT), anyString(),
                anyString(), anyString(), eq("ORDER"), eq(ORDER), any());
    }

    @Test
    void preverificationFailureDoesNotCreateInviteEvent() {
        doThrow(new IllegalStateException("completed identity not verified"))
                .when(messages).verifyReceiptFollowUp(order);
        service.runReceiptFollowUps();
        verifyNoInteractions(engagement);
        verify(messages, never()).sendReceiptFollowUp(any(), anyString());
        verify(orders).deferReceiptFollowUp(eq(240L), any());
    }

    @Test
    void genericFailureBeforeMessageWriteIsKnownAndCanRetry() {
        when(messages.sendReceiptFollowUp(order, INVITE)).thenThrow(new IllegalStateException("connection failed"));
        service.runReceiptFollowUps();
        verify(engagement).knownFailure(eq(TENANT), eq(17L), anyString(), eq(1));
        verify(engagement, never()).unknown(anyLong(), anyLong(), anyString());
        verify(orders).deferReceiptFollowUp(eq(240L), any());
        verify(orders, never()).updateReceiptFollowUpProgress(anyLong(), anyInt(), eq(1), any(), anyInt());
    }

    @Test
    void genericFailureAfterMessageWriteStopsAsUnknown() {
        when(messages.sendReceiptFollowUp(order, INVITE)).thenAnswer(invocation -> {
            DeliveryExecution.beforeExternal();
            throw new IllegalStateException("storage failed after sending");
        });
        service.runReceiptFollowUps();
        verifyUnknownInvite();
    }

    @Test
    void explicitUncertainReceiptStopsEvenWithoutObservedWriteBoundary() {
        when(messages.sendReceiptFollowUp(order, INVITE)).thenThrow(new DeliveryUncertainException("timeout"));
        service.runReceiptFollowUps();
        verifyUnknownInvite();
    }

    @Test
    void failedProgressWriteAfterSuccessCannotRepeatInvite() {
        when(orders.updateReceiptFollowUpProgress(240L, 1, 1, null, 0))
                .thenThrow(new IllegalStateException("progress write failed")).thenReturn(1);
        when(engagement.beginInvite(TENANT, ACCOUNT, 240L, ORDER, 0, INVITE))
                .thenReturn(admission(OrderEngagementService.Decision.SEND),
                        admission(OrderEngagementService.Decision.ALREADY_CONFIRMED));
        service.runReceiptFollowUps();
        service.runReceiptFollowUps();
        verify(messages, times(1)).sendReceiptFollowUp(order, INVITE);
        verify(engagement, times(1)).success(TENANT, 17L);
        verify(engagement, never()).unknown(anyLong(), anyLong(), anyString());
    }

    @Test
    void lostInviteClaimNeverAccessesPlatformOrSends() {
        when(orders.claimReceiptFollowUp(240L, 0)).thenReturn(0);
        service.runReceiptFollowUps();
        verifyNoInteractions(api, messages, engagement);
    }

    @Test
    void successfulAutomaticRatingIsVerifiedAndPersistentlyConfirmed() {
        service.runAutoRate();
        var sequence = inOrder(messages, engagement, api);
        sequence.verify(messages).verifyReceiptFollowUp(order);
        sequence.verify(engagement).beginAutoRate(TENANT, ACCOUNT, 240L, ORDER, "感谢支持，交易愉快。");
        sequence.verify(api).callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap());
        sequence.verify(engagement).success(TENANT, 17L);
        verify(orders).updateRateResult(ACCOUNT, ORDER, 1, "感谢支持，交易愉快。", "AUTO");
        verify(activation).initializeEnabled();
    }

    @ParameterizedTest
    @MethodSource("unsafeOrders")
    void unsafeLocalOrderCannotBeAutomaticallyRated(XianyuGoodsOrder unsafe) {
        order = unsafe;
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(messages, engagement);
    }

    @Test
    void activationBoundaryExcludesExistingOrderFromAutomaticRating() {
        when(activation.allowsOrder("AUTO_RATE", order)).thenReturn(false);
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(messages, engagement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"已发货", "退款中", "交易关闭", ""})
    void automaticRatingRequiresPlatformCompletedOrder(String tradeStatus) throws JsonProcessingException {
        stubPlatform(tradeStatus, "5", "5");
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(messages, engagement);
    }

    @Test
    void locallyRecordedRatingCannotBeRepeated() {
        order.setRateStatus(1);
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(messages, engagement);
    }

    @Test
    void buyerAfterRatingModeWaitsForBuyerReview() {
        goodsConfig.setXianyuAutoRateOn(XianyuGoodsConfig.AUTO_RATE_AFTER_BUYER);
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(messages, engagement);
    }

    @Test
    void buyerAfterRatingModeRatesOnceBuyerReviewed() throws JsonProcessingException {
        goodsConfig.setXianyuAutoRateOn(XianyuGoodsConfig.AUTO_RATE_AFTER_BUYER);
        stubPlatform("交易成功", "5", "4");
        service.runAutoRate();
        verify(engagement).success(TENANT, 17L);
        verify(orders).updateRateResult(ACCOUNT, ORDER, 1, "感谢支持，交易愉快。", "AUTO");
    }

    @Test
    void successfulAutoRateEventCannotRepeatDespiteStalePendingPlatformList() {
        when(engagement.beginAutoRate(eq(TENANT), eq(ACCOUNT), eq(240L), eq(ORDER), anyString()))
                .thenReturn(admission(OrderEngagementService.Decision.SEND),
                        admission(OrderEngagementService.Decision.ALREADY_CONFIRMED));
        service.runAutoRate();
        service.runAutoRate();
        verify(api, times(1)).callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap());
        verify(engagement, times(1)).success(TENANT, 17L);
    }

    @Test
    void unknownAutoRateEventCannotRepeatAndReportsReviewIssue() {
        when(engagement.beginAutoRate(eq(TENANT), eq(ACCOUNT), eq(240L), eq(ORDER), anyString()))
                .thenReturn(admission(OrderEngagementService.Decision.RESULT_UNKNOWN));
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyRateIssue();
    }

    @Test
    void rateWithoutResponseBecomesUnknownInsteadOfRetryableFailure() {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "平台接口调用异常", false));
        service.runAutoRate();
        verifyUnknownRating();
    }

    @Test
    void rateWithoutBusinessStatusBecomesUnknown() {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(true, "{\"data\":{}}", null, false));
        service.runAutoRate();
        verifyUnknownRating();
    }

    @Test
    void explicitSuccessForAnotherOrderCannotConfirmCurrentRating() throws JsonProcessingException {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("success", true, "successOrderIds", List.of("UNIT-OTHER-ORDER"))));
        service.runAutoRate();
        verifyUnknownRating();
        verify(engagement, never()).success(anyLong(), anyLong());
        verify(orders, never()).updateRateResult(eq(ACCOUNT), eq(ORDER), eq(1), anyString(), anyString());
    }

    @Test
    void conflictingSuccessAndFailureForCurrentOrderBecomesUnknown() throws JsonProcessingException {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("success", true, "successOrderIds", List.of(ORDER),
                        "failOrderInfos", List.of(Map.of("orderId", ORDER, "errorMsg", "评价被拒绝")))));
        service.runAutoRate();
        verifyUnknownRating();
        verify(engagement, never()).success(anyLong(), anyLong());
        verify(orders, never()).updateRateResult(eq(ACCOUNT), eq(ORDER), eq(1), anyString(), anyString());
    }

    @Test
    void successFlagWithoutExactOrderAcknowledgmentBecomesUnknown() throws JsonProcessingException {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("success", true)));
        service.runAutoRate();
        verifyUnknownRating();
        verify(engagement, never()).success(anyLong(), anyLong());
    }

    @Test
    void explicitCurrentFailureWithoutMessageCannotBecomeSuccess() throws JsonProcessingException {
        var result = service.normalizeRateResult(response(Map.of("success", true,
                "failOrderInfos", List.of(Map.of("orderId", ORDER)))), ORDER);
        assertFalse(result.isSuccess());
        assertEquals("平台明确拒绝当前订单评价", result.getErrorMessage());
    }

    @Test
    void explicitFailureForAnotherOrderDoesNotOverrideExactCurrentSuccess() throws JsonProcessingException {
        var result = service.normalizeRateResult(response(Map.of("success", true, "successOrderIds", List.of(ORDER),
                "failOrderInfos", List.of(Map.of("orderId", "UNIT-OTHER-ORDER", "errorMsg", "other rejected")))), ORDER);
        assertTrue(result.isSuccess());
    }

    @Test
    void explicitPlatformRejectionRemainsKnownFailure() throws JsonProcessingException {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("success", false, "failOrderInfos",
                        List.of(Map.of("orderId", ORDER, "errorMsg", "评价内容不符合要求")))));
        service.runAutoRate();
        verify(engagement).knownFailure(TENANT, 17L, "评价内容不符合要求", 1);
        verify(engagement, never()).unknown(anyLong(), anyLong(), anyString());
    }

    @Test
    void riskWaitBeforeAdmissionDoesNotConsumePersistentAttempt() {
        when(risk.tryAcquire(ACCOUNT, RiskControlService.WriteOperation.ORDER_RATE))
                .thenReturn(new RiskControlService.GuardDecision(false, RiskControlService.GuardState.RATE_WAIT,
                        60, 0, "wait", RiskControlService.WriteOperation.ORDER_RATE));
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(engagement);
    }

    @Test
    void unexpectedExceptionAfterRatingRequestCannotBeAutomaticallyRetried() {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("uncertain response"));
        service.runAutoRate();
        verifyUnknownRating();
    }

    @Test
    void autoRatePreverificationFailureDoesNotCreateEventOrWrite() {
        doThrow(new IllegalStateException("refund detected"))
                .when(messages).verifyReceiptFollowUp(order);
        service.runAutoRate();
        verifyNoRatingWrite();
        verifyNoInteractions(engagement);
    }

    @Test
    void manualRatingStillRequiresSafeConfirmedDelivery() {
        order.setDeliveryStatus("REVIEW_REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> service.manualRate(ACCOUNT, ORDER, "感谢支持"));
        verifyNoInteractions(api, messages, engagement);
    }

    @Test
    void explicitManualRatingDoesNotUseBackgroundActivationCutoff() {
        service.manualRate(ACCOUNT, ORDER, "感谢支持");
        verifyNoInteractions(activation);
        verify(messages).verifyReceiptFollowUp(order);
        verify(engagement).success(TENANT, 17L);
        verify(orders).updateRateResult(ACCOUNT, ORDER, 1, "感谢支持", "MANUAL");
    }

    @Test
    void manualRatingCannotOverrideUnknownPersistentEvent() {
        when(engagement.beginAutoRate(eq(TENANT), eq(ACCOUNT), eq(240L), eq(ORDER), anyString()))
                .thenReturn(admission(OrderEngagementService.Decision.RESULT_UNKNOWN));
        assertThrows(IllegalStateException.class, () -> service.manualRate(ACCOUNT, ORDER, "感谢支持"));
        verifyNoRatingWrite();
        verifyRateIssue();
    }

    private void verifyUnknownInvite() {
        verify(engagement).unknown(eq(TENANT), eq(17L), anyString());
        verify(engagement, never()).knownFailure(anyLong(), anyLong(), anyString(), anyInt());
        verify(orders).updateReceiptFollowUpProgress(240L, 0, 1, null, 0);
    }

    private void verifyUnknownRating() {
        verify(engagement).unknown(eq(TENANT), eq(17L), anyString());
        verify(engagement, never()).knownFailure(anyLong(), anyLong(), anyString(), anyInt());
        verifyRateIssue();
    }

    private void verifyRateIssue() {
        verify(issues).report(eq("AUTO_RATE"), eq("auto-rate:240"), eq(ACCOUNT), anyString(), anyString(),
                anyString(), eq("ORDER"), eq(ORDER), any());
    }

    private void verifyNoRatingWrite() {
        verify(api, never()).callApiWithRetry(eq(ACCOUNT), eq(RATE_API), anyMap(), anyString(), anyMap(), anyMap());
    }

    private void stubPlatform(String tradeStatus, String sellerStatus, String buyerStatus) throws JsonProcessingException {
        when(api.callApiWithRetry(eq(ACCOUNT), eq(LIST_API), anyMap(), anyString(), anyMap(), anyMap()))
                .thenReturn(response(Map.of("items", List.of(Map.of("merchantCommonData", Map.of(
                        "orderId", ORDER, "orderStatus", tradeStatus,
                        "sellerRateStatus", sellerStatus, "buyerRateStatus", buyerStatus))))));
    }

    private XianyuApiCallUtils.ApiCallResult response(Map<String, Object> module) throws JsonProcessingException {
        String json = new ObjectMapper().writeValueAsString(Map.of("data", Map.of("module", module)));
        return new XianyuApiCallUtils.ApiCallResult(true, json, null, false);
    }

    private static OrderEngagementService.Admission admission(OrderEngagementService.Decision decision) {
        return new OrderEngagementService.Admission(decision, 17L, "unit-request", 1);
    }

    private static XianyuGoodsOrder safeOrder() {
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setId(240L); order.setTenantId(TENANT); order.setXianyuAccountId(ACCOUNT);
        order.setXyGoodsId(GOODS); order.setOrderId(ORDER); order.setBuyerUserId("unit-buyer");
        order.setState(1); order.setDeliveryStatus("COMPLETED"); order.setConfirmState(1);
        order.setDeliveryMessageState(1); order.setRefundStatus("NONE");
        order.setReceiptFollowUpSentCount(0); order.setReceiptFollowUpCompleted(0);
        return order;
    }

    private static Stream<XianyuGoodsOrder> unsafeOrders() {
        Stream.Builder<XianyuGoodsOrder> cases = Stream.builder();
        for (String status : List.of("REVIEW_REQUIRED", "SKIPPED", "UNKNOWN", "FAILED", "AWAITING_MESSAGE", "PENDING")) {
            XianyuGoodsOrder order = safeOrder(); order.setDeliveryStatus(status); cases.add(order);
        }
        for (String refund : List.of("REFUNDING", "REFUNDED", "UNKNOWN", "")) {
            XianyuGoodsOrder order = safeOrder(); order.setRefundStatus(refund); cases.add(order);
        }
        for (long protectedId : List.of(229L, 230L, 232L)) {
            XianyuGoodsOrder order = safeOrder(); order.setId(protectedId);
            order.setDeliveryStatus("REVIEW_REQUIRED"); order.setDeliveryMessageState(0); cases.add(order);
        }
        XianyuGoodsOrder missingDelivery = safeOrder(); missingDelivery.setConfirmState(0);
        missingDelivery.setDeliveryMessageState(0); cases.add(missingDelivery);
        XianyuGoodsOrder incomplete = safeOrder(); incomplete.setState(0); cases.add(incomplete);
        XianyuGoodsOrder nullStatus = safeOrder(); nullStatus.setDeliveryStatus(null); cases.add(nullStatus);
        XianyuGoodsOrder nullTenant = safeOrder(); nullTenant.setTenantId(null); cases.add(nullTenant);
        return cases.build();
    }
}
