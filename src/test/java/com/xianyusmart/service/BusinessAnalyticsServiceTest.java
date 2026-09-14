package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.AccountScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class BusinessAnalyticsServiceTest {
    @AfterEach void clear(){TenantContext.clear();AccountScopeContext.clear();}

    @Test
    void dashboardScopeOptionsStayInsideTenantAndAccountScope() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        AccountMatrixService matrix=mock(AccountMatrixService.class);
        when(jdbc.queryForList(anyString(),eq(Long.class),any())).thenReturn(List.of(101L,102L,201L));
        when(named.queryForList(anyString(),any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id",101L)),List.of(Map.of("id",9L,"accountCount",2L)));
        when(matrix.summary()).thenReturn(Map.of("accountCount",2));
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),matrix,mock(OperationLogService.class));
        TenantContext.set(7L);
        AccountScopeContext.set(false,Set.of(101L,102L));

        Map<String,Object> result=service.scopeOptions();

        assertEquals(2,((Map<?,?>)result.get("accountSummary")).get("accountCount"));
        verify(matrix).summary();
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params=ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named,org.mockito.Mockito.times(2)).queryForList(sql.capture(),params.capture());
        String statements=String.join("\n",sql.getAllValues());
        assertTrue(statements.contains("tenant_id=:tenant"));
        assertTrue(statements.contains("member.tenant_id=groups.tenant_id"));
        assertTrue(statements.contains("HAVING COUNT(DISTINCT member.id)"));
        assertEquals(7L,params.getAllValues().getFirst().getValue("tenant"));
        assertEquals(List.of(101L,102L),params.getAllValues().getFirst().getValue("accounts"));
    }

    @Test
    void emptyScopeReturnsUnsyncedNullMetricsInsteadOfZero() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(Long.class),any())).thenReturn(List.of());
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,mock(NamedParameterJdbcTemplate.class),
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);
        Map<String,Object> overview=service.overview(null,null,null,null);
        @SuppressWarnings("unchecked") Map<String,Object> summary=(Map<String,Object>)overview.get("summary");
        assertEquals("UNSYNCED",summary.get("coverageStatus"));
        assertEquals("NONE",summary.get("source"));
        assertNull(summary.get("gmv"));
        assertNull(summary.get("exposureCount"));
        assertNull(summary.get("refundRate"));
    }

    @Test
    void rankingsOrderByAggregateExpressionsForMySql57OnlyFullGroupBy() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);

        Method shopRank=BusinessAnalyticsService.class.getDeclaredMethod(
                "shopRank", LocalDate.class, LocalDate.class, List.class);
        Method productRank=BusinessAnalyticsService.class.getDeclaredMethod(
                "productRank", LocalDate.class, LocalDate.class, List.class);
        shopRank.setAccessible(true);
        productRank.setAccessible(true);
        LocalDate today=LocalDate.of(2026,9,14);
        shopRank.invoke(service,today.minusDays(6),today,List.of(1L));
        productRank.invoke(service,today.minusDays(6),today,List.of(1L));

        ArgumentCaptor<String> sqlCaptor=ArgumentCaptor.forClass(String.class);
        verify(named,atLeastOnce()).queryForList(sqlCaptor.capture(),any(MapSqlParameterSource.class));
        String statements=String.join("\n",sqlCaptor.getAllValues());
        assertTrue(statements.contains("ORDER BY SUM(metric.gmv) IS NULL,SUM(metric.gmv) DESC"));
        assertTrue(statements.contains("ORDER BY SUM(metric.paid_amount) IS NULL,SUM(metric.paid_amount) DESC"));
        assertFalse(statements.contains("ORDER BY gmv IS NULL,gmv DESC"));
        assertFalse(statements.contains("ORDER BY paidAmount IS NULL,paidAmount DESC"));
        assertTrue(statements.contains("account.tenant_id=metric.tenant_id"));
        assertTrue(statements.contains("goods.tenant_id=metric.tenant_id"));
    }

    @Test
    void productAnomaliesRequireObservedDenominatorsAndExposeActionableEvidence() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        Map<String,Object> anomaly=new LinkedHashMap<>();
        anomaly.put("accountId",101L);
        anomaly.put("goodsId","QA GOODS/1");
        anomaly.put("anomalyType","HIGH_EXPOSURE_LOW_CLICK");
        anomaly.put("coverageStatus","PARTIAL");
        anomaly.put("source","QA_FIXTURE");
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(anomaly));
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);

        Method method=BusinessAnalyticsService.class.getDeclaredMethod(
                "productAnomalies", LocalDate.class, LocalDate.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> result=(List<Map<String,Object>>)method.invoke(service,
                LocalDate.of(2026,9,1),LocalDate.of(2026,9,14),List.of(101L));

        assertEquals("HIGH",result.getFirst().get("severity"));
        assertEquals("高曝光、低点击",result.getFirst().get("title"));
        assertTrue(String.valueOf(result.getFirst().get("recommendation")).contains("主图"));
        assertTrue(String.valueOf(result.getFirst().get("targetRoute")).contains("QA+GOODS%2F1"));
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(named).queryForList(sql.capture(),any(MapSqlParameterSource.class));
        assertTrue(sql.getValue().contains("SUM(metric.click_count) IS NOT NULL"));
        assertTrue(sql.getValue().contains("SUM(metric.paid_order_count) IS NOT NULL"));
    }

    @Test
    void shopAnomaliesDistinguishRefundRiskFromMissingCoverage() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        Map<String,Object> refund=new LinkedHashMap<>();
        refund.put("accountId",101L); refund.put("paidOrderCount",10L); refund.put("refundOrderCount",3L);
        Map<String,Object> sync=new LinkedHashMap<>();
        sync.put("accountId",102L); sync.put("paidOrderCount",null); sync.put("refundOrderCount",null);
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(refund,sync));
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);

        Method method=BusinessAnalyticsService.class.getDeclaredMethod(
                "anomalies", LocalDate.class, LocalDate.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> result=(List<Map<String,Object>>)method.invoke(service,
                LocalDate.of(2026,9,1),LocalDate.of(2026,9,14),List.of(101L,102L));

        assertEquals("HIGH_REFUND_RATE",result.get(0).get("anomalyType"));
        assertEquals("/orders?accountId=101",result.get(0).get("targetRoute"));
        assertEquals("DATA_SYNC_DEGRADED",result.get(1).get("anomalyType"));
        assertEquals("/accounts?accountId=102",result.get(1).get("targetRoute"));
    }

    @Test
    void activeProductMetricUsesPeakOfDailyCrossShopSum() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        when(named.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Number.class))).thenReturn(1518L);
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);

        Method method=BusinessAnalyticsService.class.getDeclaredMethod(
                "activeProductPeak", LocalDate.class, LocalDate.class, List.class);
        method.setAccessible(true);
        Object value=method.invoke(service,LocalDate.of(2026,9,8),LocalDate.of(2026,9,14),List.of(101L,102L,103L));

        assertEquals(1518L,value);
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(named).queryForObject(sql.capture(),any(MapSqlParameterSource.class),eq(Number.class));
        assertTrue(sql.getValue().contains("SELECT metric_date,SUM(active_product_count) day_active"));
        assertTrue(sql.getValue().contains("SELECT MAX(day_active)"));
    }

    @Test
    void trendCoverageRequiresEveryRequestedAccountAndEverySourceRowToBeFull() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,named,
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(AccountMatrixService.class),mock(OperationLogService.class));
        TenantContext.set(9L);

        Method method=BusinessAnalyticsService.class.getDeclaredMethod(
                "trend", LocalDate.class, LocalDate.class, List.class);
        method.setAccessible(true);
        method.invoke(service,LocalDate.of(2026,9,14),LocalDate.of(2026,9,14),List.of(101L,102L));

        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(named).query(sql.capture(),any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
        assertTrue(sql.getValue().contains("COUNT(*) sourceRows"));
        assertTrue(sql.getValue().contains("SUM(CASE WHEN coverage_status='FULL' THEN 1 ELSE 0 END) fullRows"));
    }
}
