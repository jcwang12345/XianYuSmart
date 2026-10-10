package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.UniformCardDeliveryReqDTO;
import com.xianyusmart.entity.*;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UniformCardConfigurationTest {
    private final AutoDeliveryConfigServiceImpl service = new AutoDeliveryConfigServiceImpl();
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final UniformCardDeliveryPolicyService policy = mock(UniformCardDeliveryPolicyService.class);
    private final GoodsSkuReadinessService readiness = mock(GoodsSkuReadinessService.class);
    private final OperationLogService logs = mock(OperationLogService.class);
    private final XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
    private final XianyuAccount account = new XianyuAccount();

    @BeforeEach void setup() {
        TenantContext.set(7L);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configs);
        ReflectionTestUtils.setField(service, "accountAccessService", access);
        ReflectionTestUtils.setField(service, "uniformCardDeliveryPolicyService", policy);
        ReflectionTestUtils.setField(service, "goodsSkuReadinessService", readiness);
        ReflectionTestUtils.setField(service, "operationLogService", logs);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        config.setId(10L); config.setXianyuAccountId(1L); config.setXyGoodsId("G-1");
        config.setDeliveryMode(2); config.setKamiConfigIds("3"); config.setDeliveryMessageTemplate("Code: {deliveryContent}");
        config.setVoucherDeliveryEnabled(1); config.setChatDeliveryEnabled(1); config.setAutoConfirmShipment(1);
        account.setId(1L); account.setTenantId(7L); account.setStatus(1);
        when(accounts.selectOne(any())).thenReturn(account);
        when(configs.selectOne(any())).thenReturn(config);
        when(readiness.inspect(1L,"G-1")).thenReturn(new GoodsSkuReadinessService.Readiness(
                0,0,GoodsSkuReadinessService.Status.PARTIAL,"not verified"));
    }
    @AfterEach void clear(){TenantContext.clear();}
    private UniformCardDeliveryReqDTO request(boolean enabled) {
        var req = new UniformCardDeliveryReqDTO(); req.setXianyuAccountId(1L); req.setXyGoodsId("G-1");
        req.setConfigId(10L); req.setEnabled(enabled); req.setConfirmAllVariantsSameEntitlement(true); return req;
    }
    @Test void enrollmentIsSeparateFromConfigAndSnapshotAndUsesRequiredAudit() {
        var before = new ObjectMapper().findAndRegisterModules().valueToTree(config);
        when(readiness.permitsUniformCard(1L,"G-1",config)).thenReturn(true);
        var response=service.configureUniformCard(request(true));
        assertEquals(200,response.getCode()); assertEquals("PARTIAL",response.getData().getSkuReadinessStatus());
        assertTrue(response.getData().getUniformCardDeliveryEnabled()); assertTrue(response.getData().getConfigurationReady());
        assertEquals(before,new ObjectMapper().findAndRegisterModules().valueToTree(config));
        var order=inOrder(access, accounts, configs, policy, logs);
        order.verify(access).requireAccess(1L); order.verify(accounts).selectOne(any());
        order.verify(configs).selectOne(any()); order.verify(policy).permits(1L,"G-1",config);
        order.verify(policy).configure(7L,config,true); order.verify(logs).logRequired(any());
        verify(configs,never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(configs,never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        ArgumentCaptor<XianyuOperationLog> audit=ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(logs).logRequired(audit.capture());
        assertEquals("UNIFORM_CARD_DELIVERY_POLICY",audit.getValue().getOperationType());
        assertTrue(audit.getValue().getFieldDiffJson().contains("SAME_ENTITLEMENT_ALL_VARIANTS"));
        assertFalse(audit.getValue().getFieldDiffJson().contains("Code:"));
    }
    @Test void explicitDeclarationRequired(){
        var req=request(true); req.setConfirmAllVariantsSameEntitlement(false);
        assertEquals(400,assertThrows(BusinessException.class,()->service.configureUniformCard(req)).getCode());
        verifyNoInteractions(policy,logs,access);
    }
    @Test void ordinaryDisableDoesNotRequireDeclaration(){
        var req=request(false); req.setConfirmAllVariantsSameEntitlement(null);
        assertEquals(200,service.configureUniformCard(req).getCode());
        verify(policy).configure(7L,config,false);
    }
    @Test void fixedTemplateIsNotAccepted(){
        config.setDeliveryMode(1);
        assertEquals(400,assertThrows(BusinessException.class,()->service.configureUniformCard(request(true))).getCode());
        verifyNoInteractions(policy,logs);
    }
    @Test void crossAccountOrConfigMismatchDoesNotWrite(){
        config.setXianyuAccountId(2L);
        assertEquals(404,assertThrows(BusinessException.class,()->service.configureUniformCard(request(true))).getCode());
        verifyNoInteractions(policy,logs);
    }
    @Test void tenantMismatchDoesNotWrite(){
        account.setTenantId(8L);
        assertEquals(404,assertThrows(BusinessException.class,()->service.configureUniformCard(request(true))).getCode());
        verifyNoInteractions(policy,logs);
    }
    @Test void revokedAccessDoesNotWrite(){
        doThrow(new BusinessException(403,"denied")).when(access).requireAccess(1L);
        assertThrows(BusinessException.class,()->service.configureUniformCard(request(true)));
        verifyNoInteractions(policy,logs,configs,accounts);
    }
    @Test void policyFailureStopsAuditAndDoesNotRewriteConfig(){
        doThrow(new BusinessException(409,"conflict")).when(policy).configure(7L,config,true);
        assertThrows(BusinessException.class,()->service.configureUniformCard(request(true)));
        verifyNoInteractions(logs); verify(configs,never()).updateById(any(XianyuGoodsAutoDeliveryConfig.class));
    }
    @Test void auditFailureEscapesAndTransactionRollsBack(){
        doThrow(new IllegalStateException("audit failure")).when(logs).logRequired(any());
        assertThrows(IllegalStateException.class,()->service.configureUniformCard(request(true)));
        var tx=new AnnotationTransactionAttributeSource().getTransactionAttribute(
                assertDoesNotThrow(()->AutoDeliveryConfigServiceImpl.class.getMethod("configureUniformCard",UniformCardDeliveryReqDTO.class)),
                AutoDeliveryConfigServiceImpl.class);
        assertNotNull(tx); assertTrue(tx.rollbackOn(new IllegalStateException()));
    }
}
