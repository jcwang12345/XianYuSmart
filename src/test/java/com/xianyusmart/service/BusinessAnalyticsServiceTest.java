package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
