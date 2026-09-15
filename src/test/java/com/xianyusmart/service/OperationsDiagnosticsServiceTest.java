package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsDiagnosticsServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void diagnosticsUseTenantInsteadOfOperatorUserIdAndExposeEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L))).thenReturn(List.of());
        OperationsDiagnosticsService service = new OperationsDiagnosticsService(
                jdbc, mock(WebSocketService.class), mock(SystemUpdateService.class));
        UserContext.set(42L, "tenant-seven-owner", 7L);

        assertEquals(7L, service.requireTenantId());
        Map<String, Object> overview = service.overview(false);

        assertEquals("HEALTHY", overview.get("overallStatus"));
        assertNotNull(overview.get("evidenceTime"));
        assertTrue(String.valueOf(overview.get("countRelationship")).contains("独立统计"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) overview.get("checks");
        assertEquals(8, checks.size());
        assertTrue(checks.stream().allMatch(check -> check.get("impact") != null
                && check.get("source") != null && check.get("evidenceTime") != null));
    }
}
