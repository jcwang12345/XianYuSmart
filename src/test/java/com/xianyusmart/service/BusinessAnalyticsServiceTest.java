package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    @AfterEach void clear(){TenantContext.clear();}

    @Test
    void emptyScopeReturnsUnsyncedNullMetricsInsteadOfZero() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(Long.class),any())).thenReturn(List.of());
        BusinessAnalyticsService service=new BusinessAnalyticsService(jdbc,mock(NamedParameterJdbcTemplate.class),
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(OperationLogService.class));
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
                mock(AccountAccessService.class),mock(AccountGroupService.class),mock(OperationLogService.class));
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
    }
}
