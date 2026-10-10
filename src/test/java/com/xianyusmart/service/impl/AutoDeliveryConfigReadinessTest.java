package com.xianyusmart.service.impl;

import com.xianyusmart.controller.dto.AutoDeliveryConfigReqDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.xianyusmart.entity.*;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDeliveryConfigReadinessTest {
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuGoodsInfoMapper goods = mock(XianyuGoodsInfoMapper.class);
    private final GoodsSkuService skus = mock(GoodsSkuService.class);
    private final XianyuGoodsAutoDeliveryConfigMapper configs = mock(XianyuGoodsAutoDeliveryConfigMapper.class);
    private final FixedDeliveryTemplateService templates = mock(FixedDeliveryTemplateService.class);
    private final BuyerMessageService messages = mock(BuyerMessageService.class);
    private final XianyuGoodsConfigMapper switches = mock(XianyuGoodsConfigMapper.class);
    private final XianyuKamiConfigMapper pools = mock(XianyuKamiConfigMapper.class);
    private final SharedAccountLinkMapper links = mock(SharedAccountLinkMapper.class);
    private AutoDeliveryConfigServiceImpl service;
    private XianyuGoodsInfo item;
    private XianyuGoodsAutoDeliveryConfig stored;

    @BeforeEach
    void setUp() {
        service = new AutoDeliveryConfigServiceImpl();
        GoodsSkuReadinessService readiness = new GoodsSkuReadinessService();
        ReflectionTestUtils.setField(readiness, "goodsInfoMapper", goods);
        ReflectionTestUtils.setField(readiness, "goodsSkuService", skus);
        ReflectionTestUtils.setField(readiness, "deliveryConfigMapper", configs);
        ReflectionTestUtils.setField(readiness, "fixedDeliveryTemplateService", templates);
        ReflectionTestUtils.setField(service, "goodsSkuReadinessService", readiness);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "goodsInfoMapper", goods);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configs);
        ReflectionTestUtils.setField(service, "fixedDeliveryTemplateService", templates);
        ReflectionTestUtils.setField(service, "buyerMessageService", messages);
        ReflectionTestUtils.setField(service, "kamiConfigMapper", pools);
        ReflectionTestUtils.setField(service, "sharedAccountLinkMapper", links);
        // The before-source reproducer does not yet have the switch dependency.
        if (org.springframework.util.ReflectionUtils.findField(service.getClass(), "goodsConfigMapper") != null)
            ReflectionTestUtils.setField(service, "goodsConfigMapper", switches);
        XianyuAccount account = new XianyuAccount(); account.setId(101L); account.setTenantId(1L);
        when(accounts.selectById(101L)).thenReturn(account);
        item = new XianyuGoodsInfo(); item.setId(55L); item.setXianyuAccountId(101L);
        item.setXyGoodId("G-1"); item.setSkuCount(0); item.setCoverageStatus("PARTIAL");
        when(goods.selectOne(any())).thenAnswer(i -> item);
        when(goods.selectCount(any())).thenAnswer(i -> item == null ? 0L : 1L);
        when(skus.countByXyGoodsId("G-1", 101L)).thenReturn(0);
        XianyuFixedDeliveryTemplate template = new XianyuFixedDeliveryTemplate(); template.setId(8L);
        when(templates.findOwnedTemplate(101L, 8L)).thenReturn(template);
        when(messages.normalizeReceiptFollowUpMessages(any())).thenReturn("[]");
        when(messages.normalizeReceiptFollowUpInterval(any())).thenReturn(5);
        when(configs.insert(any(XianyuGoodsAutoDeliveryConfig.class))).thenAnswer(i -> {
            stored = i.getArgument(0); stored.setId(9L); return 1;
        });
        when(configs.findByAccountIdAndGoodsIdNoSku(101L, "G-1")).thenAnswer(i -> stored != null && stored.getSkuId() == null ? stored : null);
        when(configs.findByAccountIdAndGoodsId(101L, "G-1")).thenAnswer(i -> stored == null ? List.of() : List.of(stored));
    }

    @Test
    void savesFixedTemplateWhileDeliveryDisabledAndZeroOfZeroPartial() {
        var result = service.saveOrUpdateConfig(request());
        assertEquals(200, result.getCode(), result.getMsg());
        assertEquals(8L, stored.getFixedTemplateId());
        assertNull(stored.getSkuId());
        assertTrue(result.getData().getConfigurationReady());
        assertEquals("PARTIAL", result.getData().getSkuReadinessStatus());
        AutoDeliveryConfigQueryReqDTO query = new AutoDeliveryConfigQueryReqDTO();
        query.setXianyuAccountId(101L); query.setXyGoodsId("G-1");
        var readback = service.getConfig(query);
        assertEquals(200, readback.getCode());
        assertEquals(8L, readback.getData().getFixedTemplateId());
        assertTrue(readback.getData().getConfigurationReady());
        verify(switches, never()).update(any());
        verify(pools, never()).updateById(any());
        verify(messages, never()).queueDeliveryMessage(any(), anyString());
        verify(messages, never()).activateAndSendDeliveryMessage(any());
    }

    @Test
    void trustedEmptySnapshotSavesReadyWithoutTurningDeliveryOn() {
        item.setCoverageStatus("FULL");
        var result = service.saveOrUpdateConfig(request());
        assertEquals(200, result.getCode());
        assertTrue(result.getData().getConfigurationReady());
        assertEquals("EMPTY_VERIFIED", result.getData().getSkuReadinessStatus());
        verify(switches, never()).update(any());
    }

    @Test
    void partialRealChildCanBeConfiguredButIsNotReady() {
        item.setSkuCount(4);
        when(skus.countByXyGoodsId("G-1", 101L)).thenReturn(2);
        XianyuGoodsSku sku = new XianyuGoodsSku(); sku.setSkuId("SKU-1"); sku.setValueText("版本一");
        when(skus.findByXyGoodsIdAndSkuId("G-1", 101L, "SKU-1")).thenReturn(sku);
        var req = request(); req.setSkuId(" SKU-1 "); req.setSkuName("伪造名称");
        var result = service.saveOrUpdateConfig(req);
        assertEquals(200, result.getCode());
        assertEquals("SKU-1", stored.getSkuId()); assertEquals("版本一", stored.getSkuName());
        assertFalse(result.getData().getConfigurationReady());
    }

    @Test
    void unknownGoodsLevelDraftCanSaveEvenWithDeclaredMissingChildren() {
        item.setSkuCount(4);
        var result = service.saveOrUpdateConfig(request());
        assertEquals(200, result.getCode()); assertFalse(result.getData().getConfigurationReady());
    }

    @Test
    void forgedOrCrossAccountSkuIsRejectedBeforeWrite() {
        var req = request(); req.setSkuId("other-account-sku");
        assertEquals(500, service.saveOrUpdateConfig(req).getCode());
        verify(configs, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    @Test
    void refusesIncompleteChangesWhileAlreadyEnabled() {
        XianyuGoodsConfig enabled = new XianyuGoodsConfig(); enabled.setXianyuAutoDeliveryOn(1);
        when(switches.selectByAccountAndGoodsId(101L, "G-1")).thenReturn(enabled);
        var result = service.saveOrUpdateConfig(request());
        assertEquals(500, result.getCode()); assertTrue(result.getMsg().contains("先关闭自动发货"));
        verify(configs, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
        verify(configs, never()).updateById(any()); verify(switches, never()).update(any());
    }

    @Test
    void rejectsForeignTemplateAndLocalGoodsId() {
        var templateReq = request(); templateReq.setFixedTemplateId(999L);
        assertEquals(500, service.saveOrUpdateConfig(templateReq).getCode());
        var goodsReq = request(); goodsReq.setXianyuGoodsId(999L);
        assertEquals(500, service.saveOrUpdateConfig(goodsReq).getCode());
        verify(configs, never()).insert(any(XianyuGoodsAutoDeliveryConfig.class));
    }

    @Test
    void rejectsForeignCardPoolButCanSaveOwnedPoolWithoutConsumption() {
        var req = request(); req.setDeliveryMode(2); req.setKamiConfigIds("12");
        assertEquals(500, service.saveOrUpdateConfig(req).getCode());
        XianyuKamiConfig pool = new XianyuKamiConfig(); pool.setId(12L);
        when(pools.selectById(12L)).thenReturn(pool);
        when(links.selectKamiConfigAccounts(12L)).thenReturn(List.of(101L));
        when(messages.normalizeDeliveryMessageTemplate(any())).thenReturn("{deliveryContent}");
        var result = service.saveOrUpdateConfig(req);
        assertEquals(200, result.getCode()); assertFalse(result.getData().getConfigurationReady());
        verify(pools, never()).updateById(any());
    }

    private AutoDeliveryConfigReqDTO request() {
        AutoDeliveryConfigReqDTO req = new AutoDeliveryConfigReqDTO();
        req.setXianyuAccountId(101L); req.setXianyuGoodsId(55L); req.setXyGoodsId("G-1");
        req.setDeliveryMode(1); req.setFixedTemplateId(8L); req.setVoucherDeliveryEnabled(1);
        req.setChatDeliveryEnabled(1); req.setReceiptFollowUpMessages("[]");
        req.setReceiptFollowUpIntervalSeconds(5); return req;
    }
}
