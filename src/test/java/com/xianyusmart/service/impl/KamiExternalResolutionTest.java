package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiExternalResolutionTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock XianyuKamiConfigMapper configMapper;
    @Mock XianyuKamiItemMapper itemMapper;
    @Mock OperationLogService operationLogService;
    private KamiConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KamiConfigServiceImpl();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "kamiConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "kamiItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "sharedAccountLinkMapper", mock(SharedAccountLinkMapper.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        TenantContext.set(1L);
        UserContext.set(7L, "qa-owner", 1L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void confirmedSupplyAttachesExactCardsAsReviewRequiredWithoutLeakingThemIntoAudit() {
        Map<String, Object> request = unknownRequest();
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(request));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        XianyuKamiConfig config = config();
        when(configMapper.selectById(31L)).thenReturn(config);
        when(configMapper.lockById(31L)).thenReturn(config);
        when(itemMapper.countByConfigId(31L)).thenReturn(0);
        when(itemMapper.countByConfigIdAndContent(eq(31L), anyString())).thenReturn(0);
        when(itemMapper.countUsed(31L)).thenReturn(0);
        doAnswer(invocation -> {
            XianyuKamiItem item = invocation.getArgument(0);
            item.setId(100L + item.getSortOrder());
            return 1;
        }).when(itemMapper).insert(any(XianyuKamiItem.class));

        var result = service.resolveExternalRequest(91L, "CONFIRMED_SUPPLIED",
                "确认供应商已出卡，附加2条卡密并转人工核对订单QA-ORDER-1",
                List.of("CARD-A", "CARD-B"), "供应商工单已核对", "qa-resolution-1");

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().get("attachedReviewCount"));
        ArgumentCaptor<XianyuKamiItem> itemCaptor = ArgumentCaptor.forClass(XianyuKamiItem.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertTrue(itemCaptor.getAllValues().stream().allMatch(item -> item.getStatus() == 3));
        ArgumentCaptor<XianyuOperationLog> auditCaptor = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(operationLogService).logRequired(auditCaptor.capture());
        String audit = auditCaptor.getValue().getRequestParams() + auditCaptor.getValue().getResponseResult();
        assertFalse(audit.contains("CARD-A"));
        assertFalse(audit.contains("CARD-B"));
        assertTrue(audit.contains("cardContentStoredInAudit"));
    }

    @Test
    void confirmedNotSuppliedCreatesNoCardAndLeavesAuditedRetryEvidence() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(unknownRequest()));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        XianyuKamiConfig config = config();
        when(configMapper.selectById(31L)).thenReturn(config);
        when(configMapper.lockById(31L)).thenReturn(config);
        when(itemMapper.countByConfigId(31L)).thenReturn(0);
        when(itemMapper.countUsed(31L)).thenReturn(0);

        var result = service.resolveExternalRequest(91L, "CONFIRMED_NOT_SUPPLIED",
                "确认供应商未出卡，可安全重试订单QA-ORDER-1",
                List.of(), null, "qa-resolution-2");

        assertEquals("MANUAL_NOT_SUPPLIED", result.getData().get("requestStatus"));
        verify(itemMapper, never()).insert(any(XianyuKamiItem.class));
        verify(operationLogService).logRequired(any(XianyuOperationLog.class));
    }

    private Map<String, Object> unknownRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", 91L);
        request.put("configId", 31L);
        request.put("accountId", 101L);
        request.put("orderId", "QA-ORDER-1");
        request.put("quantity", 2);
        request.put("requestStatus", "REVIEW_REQUIRED");
        request.put("resultUnknown", 1);
        request.put("resolutionDecision", null);
        request.put("resolutionRequestId", null);
        return request;
    }

    private XianyuKamiConfig config() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(31L);
        config.setXianyuAccountId(101L);
        config.setConfigVersion(4L);
        config.setSourceType("API");
        return config;
    }
}
