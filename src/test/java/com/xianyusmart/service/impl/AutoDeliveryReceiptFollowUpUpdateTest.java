package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.ReceiptFollowUpUpdateReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDeliveryReceiptFollowUpUpdateTest {
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsInfoMapper goods = mock(XianyuGoodsInfoMapper.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final XianyuGoodsConfigMapper switches = mock(XianyuGoodsConfigMapper.class);
    private final GoodsSkuReadinessService readiness = mock(GoodsSkuReadinessService.class);
    private final FixedDeliveryTemplateService templates = mock(FixedDeliveryTemplateService.class);
    private final XianyuKamiConfigMapper pools = mock(XianyuKamiConfigMapper.class);
    private final SharedAccountLinkMapper links = mock(SharedAccountLinkMapper.class);
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final AutomationActivationService activation = mock(AutomationActivationService.class);
    private final OperationLogService logs = mock(OperationLogService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final BuyerMessageService messages = new BuyerMessageService(
            mock(WebSocketService.class), mock(SentMessageSaveService.class),
            mock(XianyuGoodsOrderMapper.class), json, mock(org.springframework.context.ApplicationEventPublisher.class));
    private final AutoDeliveryConfigServiceImpl service = new AutoDeliveryConfigServiceImpl();
    private final XianyuAccount account = new XianyuAccount();
    private final XianyuGoodsInfo item = new XianyuGoodsInfo();
    private final XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
    private final XianyuGoodsConfig enabled = new XianyuGoodsConfig();

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "goodsInfoMapper", goods);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configs);
        ReflectionTestUtils.setField(service, "goodsConfigMapper", switches);
        ReflectionTestUtils.setField(service, "goodsSkuReadinessService", readiness);
        ReflectionTestUtils.setField(service, "fixedDeliveryTemplateService", templates);
        ReflectionTestUtils.setField(service, "kamiConfigMapper", pools);
        ReflectionTestUtils.setField(service, "sharedAccountLinkMapper", links);
        ReflectionTestUtils.setField(service, "buyerMessageService", messages);
        ReflectionTestUtils.setField(service, "accountAccessService", access);
        ReflectionTestUtils.setField(service, "automationActivationService", activation);
        ReflectionTestUtils.setField(service, "operationLogService", logs);
        ReflectionTestUtils.setField(service, "objectMapper", json);
        account.setId(1L); account.setTenantId(7L); account.setStatus(1);
        item.setId(55L); item.setXianyuAccountId(1L); item.setXyGoodId("G-1");
        item.setCoverageStatus("PARTIAL"); item.setSkuCount(0);
        enabled.setXianyuAutoDeliveryOn(1);
        config.setId(10L); config.setXianyuAccountId(1L); config.setXianyuGoodsId(55L); config.setXyGoodsId("G-1");
        config.setDeliveryMode(1); config.setFixedTemplateId(8L); config.setAutoDeliveryContent("kept content");
        config.setKamiConfigIds("kept pool"); config.setKamiDeliveryTemplate("kept card template");
        config.setDeliveryMessageTemplate("kept delivery template"); config.setAutoDeliveryImageUrl("kept image");
        config.setVoucherDeliveryEnabled(1); config.setChatDeliveryEnabled(0);
        config.setAutoConfirmShipment(1); config.setRagDelaySeconds(15);
        config.setReceiptFollowUpMessages("[\"previous invitation\"]");
        config.setReceiptFollowUpIntervalSeconds(30);
        when(accounts.selectOne(any())).thenReturn(account);
        when(goods.selectOne(any())).thenReturn(item);
        when(configs.findOwnedReceiptFollowUpConfig(7L, 1L, "G-1", null)).thenReturn(config);
        when(configs.updateReceiptFollowUp(eq(7L), eq(10L), eq(1L), eq("G-1"), isNull(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    config.setReceiptFollowUpMessages(invocation.getArgument(5));
                    config.setReceiptFollowUpIntervalSeconds(invocation.getArgument(6));
                    return 1;
                });
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void enabledPartialItemCanUpdateOnlyInvitationWithoutChangingDelivery() {
        ObjectNode before = deliverySnapshot();
        var result = service.updateReceiptFollowUp(request());
        assertEquals(200, result.getCode());
        assertEquals("[\"Thanks for your purchase\"]", config.getReceiptFollowUpMessages());
        assertEquals(15, config.getReceiptFollowUpIntervalSeconds());
        assertEquals(before, deliverySnapshot());
        assertEquals("PARTIAL", item.getCoverageStatus());
        assertEquals(1, enabled.getXianyuAutoDeliveryOn());
        verifyNoInteractions(readiness, switches, templates, pools, links);
        verify(configs, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(configs, never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
        var calls = inOrder(accounts, goods, configs, activation, logs);
        calls.verify(accounts).selectOne(any());
        calls.verify(goods).selectOne(any());
        calls.verify(configs).findOwnedReceiptFollowUpConfig(7L, 1L, "G-1", null);
        calls.verify(activation).ensureActivated("REVIEW_INVITE", 7L, 1L, "G-1");
        calls.verify(configs).updateReceiptFollowUp(7L, 10L, 1L, "G-1", null,
                "[\"Thanks for your purchase\"]", 15);
        calls.verify(logs).logRequired(any());
    }

    @Test
    void auditStoresOnlyCountsHashesAndIntervals() throws Exception {
        service.updateReceiptFollowUp(request());
        ArgumentCaptor<XianyuOperationLog> captured = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(logs).logRequired(captured.capture());
        XianyuOperationLog audit = captured.getValue();
        var diff = json.readTree(audit.getFieldDiffJson());
        assertEquals(1, diff.path("before").path("messageCount").asInt());
        assertEquals(30, diff.path("before").path("intervalSeconds").asInt());
        assertEquals(1, diff.path("after").path("messageCount").asInt());
        assertEquals(15, diff.path("after").path("intervalSeconds").asInt());
        assertEquals(64, diff.path("before").path("messagesHash").asText().length());
        assertEquals(64, diff.path("after").path("messagesHash").asText().length());
        assertFalse(audit.getFieldDiffJson().contains("previous invitation"));
        assertFalse(audit.getFieldDiffJson().contains("Thanks for your purchase"));
        assertNull(audit.getRequestParams()); assertNull(audit.getResponseResult());
        assertEquals("LOCAL_SUCCESS", audit.getOutcomeState());
        assertEquals("RECEIPT_FOLLOW_UP_UPDATE", audit.getOperationType());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "[]", "[\"  \",null]"})
    void emptyInvitationDisablesOnlyMessagesAndDoesNotDeleteOrEnrollBoundary(String value) {
        var request = request(); request.setReceiptFollowUpMessages(value);
        request.setReceiptFollowUpIntervalSeconds(null);
        assertEquals(200, service.updateReceiptFollowUp(request).getCode());
        assertEquals("[]", config.getReceiptFollowUpMessages());
        assertEquals(5, config.getReceiptFollowUpIntervalSeconds());
        verifyNoInteractions(activation);
        verify(logs).logRequired(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-id", "account-tenant", "goods-account", "goods-id", "config-account",
            "config-goods", "config-local-goods", "config-sku"})
    void rejectsCrossAccountOrInconsistentOwnershipBeforeWrite(String mismatch) {
        switch (mismatch) {
            case "account-id" -> account.setId(2L);
            case "account-tenant" -> account.setTenantId(8L);
            case "goods-account" -> item.setXianyuAccountId(2L);
            case "goods-id" -> item.setXyGoodId("OTHER");
            case "config-account" -> config.setXianyuAccountId(2L);
            case "config-goods" -> config.setXyGoodsId("OTHER");
            case "config-local-goods" -> config.setXianyuGoodsId(99L);
            case "config-sku" -> config.setSkuId("OTHER");
        }
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateReceiptFollowUp(request())).getCode());
        assertNoWrite();
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "goods", "config"})
    void requiresExistingOwnedAccountGoodsAndConfig(String missing) {
        switch (missing) {
            case "account" -> when(accounts.selectOne(any())).thenReturn(null);
            case "goods" -> when(goods.selectOne(any())).thenReturn(null);
            case "config" -> when(configs.findOwnedReceiptFollowUpConfig(7L, 1L, "G-1", null)).thenReturn(null);
        }
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateReceiptFollowUp(request())).getCode());
        assertNoWrite();
    }

    @Test
    void specificSkuNeverFallsBackToGenericConfig() {
        var request = request(); request.setSkuId(" SKU-1 ");
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.updateReceiptFollowUp(request)).getCode());
        verify(configs).findOwnedReceiptFollowUpConfig(7L, 1L, "G-1", "SKU-1");
        verify(configs, never()).findOwnedReceiptFollowUpConfig(7L, 1L, "G-1", null);
        assertNoWrite();
    }

    @Test
    void missingTenantAndRestrictedAccountScopeAreRejectedBeforeAnyRead() {
        TenantContext.clear();
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.updateReceiptFollowUp(request())).getCode());
        TenantContext.set(7L);
        doThrow(new BusinessException(403, "denied")).when(access).requireAccess(1L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.updateReceiptFollowUp(request())).getCode());
        verifyNoInteractions(accounts, goods, configs, activation, logs);
    }

    @Test
    void invalidMessagesAndIntervalsCannotPersistOrEnroll() {
        var malformed = request(); malformed.setReceiptFollowUpMessages("not JSON");
        assertThrows(IllegalArgumentException.class, () -> service.updateReceiptFollowUp(malformed));
        var invalidInterval = request(); invalidInterval.setReceiptFollowUpIntervalSeconds(4);
        assertThrows(IllegalArgumentException.class, () -> service.updateReceiptFollowUp(invalidInterval));
        assertNoWrite();
    }

    @Test
    void activationAndAuditFailuresPropagateForTransactionRollback() throws Exception {
        var attributes = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                AutoDeliveryConfigServiceImpl.class.getMethod("updateReceiptFollowUp", ReceiptFollowUpUpdateReqDTO.class),
                AutoDeliveryConfigServiceImpl.class);
        assertNotNull(attributes);
        assertTrue(attributes.rollbackOn(new IllegalStateException("failed")));
        doThrow(new IllegalStateException("enrollment failed")).when(activation)
                .ensureActivated("REVIEW_INVITE", 7L, 1L, "G-1");
        assertThrows(IllegalStateException.class, () -> service.updateReceiptFollowUp(request()));
        verify(configs, never()).updateReceiptFollowUp(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), anyInt());
        verifyNoInteractions(logs);
        reset(activation);
        doThrow(new IllegalStateException("audit failed")).when(logs).logRequired(any());
        assertThrows(IllegalStateException.class, () -> service.updateReceiptFollowUp(request()));
    }

    @Test
    void mapperSqlScopesTenantAndUpdatesExactlyTwoReceiptFields() throws Exception {
        var method = XianyuGoodsAutoDeliveryConfigMapper.class.getMethod("updateReceiptFollowUp",
                Long.class, Long.class, Long.class, String.class, String.class, String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        String setClause = sql.substring(sql.indexOf("SET ") + 4, sql.indexOf(" WHERE "));
        assertEquals("receipt_follow_up_messages=#{messages}, receipt_follow_up_interval_seconds=#{intervalSeconds}", setClause);
        assertTrue(sql.contains("tenant_id=#{tenantId}")); assertTrue(sql.contains("id=#{configId}"));
        assertTrue(sql.contains("xianyu_account_id=#{accountId}")); assertTrue(sql.contains("xy_goods_id=#{goodsId}"));
        assertTrue(sql.contains("sku_key=COALESCE(#{skuId}, '')"));
        String read = String.join(" ", XianyuGoodsAutoDeliveryConfigMapper.class
                .getMethod("findOwnedReceiptFollowUpConfig", Long.class, Long.class, String.class, String.class)
                .getAnnotation(Select.class).value());
        assertTrue(read.contains("tenant_id=#{tenantId}")); assertTrue(read.endsWith("FOR UPDATE"));
    }

    private void assertNoWrite() {
        verify(configs, never()).updateReceiptFollowUp(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), anyInt());
        verify(configs, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(configs, never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
        verifyNoInteractions(activation, logs);
    }

    private ObjectNode deliverySnapshot() {
        ObjectNode tree = json.valueToTree(config);
        tree.remove(List.of("receiptFollowUpMessages", "receiptFollowUpIntervalSeconds"));
        return tree;
    }

    private ReceiptFollowUpUpdateReqDTO request() {
        var request = new ReceiptFollowUpUpdateReqDTO();
        request.setXianyuAccountId(1L); request.setXyGoodsId("G-1");
        request.setReceiptFollowUpMessages("[\"  Thanks for your purchase  \"]");
        request.setReceiptFollowUpIntervalSeconds(15);
        return request;
    }
}
