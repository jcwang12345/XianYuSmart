package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.VersionInfoRespDTO;
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

    @Test
    void unavailableUpdateAgentPreventsLatestVersionFromBeingReportedHealthy() {
        JdbcTemplate jdbc = zeroCountJdbc(7L);
        SystemUpdateService updateService = mock(SystemUpdateService.class);
        VersionInfoRespDTO version = new VersionInfoRespDTO();
        version.setCurrentVersion("4.0.0-rc.1");
        version.setLatestVersion("4.0.0-rc.1");
        version.setHasUpdate(false);
        when(updateService.checkUpdate()).thenReturn(version);
        when(updateService.updateAgentStatus()).thenReturn(Map.of("available", false));
        OperationsDiagnosticsService service = new OperationsDiagnosticsService(
                jdbc, mock(WebSocketService.class), updateService);
        UserContext.set(42L, "tenant-seven-owner", 7L);

        Map<String, Object> overview = service.overview(true);

        // 更新代理“确定不可用”仍是可执行 WARNING，但版本结论本身必须进入 UNKNOWN。
        assertEquals("WARNING", overview.get("overallStatus"));
        assertEquals(1L, overview.get("unknownCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) overview.get("checks");
        Map<String, Object> versionCheck = checks.stream()
                .filter(check -> "VERSION".equals(check.get("key")))
                .findFirst().orElseThrow();
        assertEquals("UNKNOWN", versionCheck.get("status"));
        assertTrue(String.valueOf(versionCheck.get("action")).contains("更新代理未就绪"));
        Map<String, Object> agentCheck = checks.stream()
                .filter(check -> "UPDATE_AGENT".equals(check.get("key")))
                .findFirst().orElseThrow();
        assertEquals("WARNING", agentCheck.get("status"));
    }

    @Test
    void updateAgentStatusFailurePropagatesUnknownToBothChecks() {
        JdbcTemplate jdbc = zeroCountJdbc(7L);
        SystemUpdateService updateService = mock(SystemUpdateService.class);
        VersionInfoRespDTO version = new VersionInfoRespDTO();
        version.setCurrentVersion("4.0.0-rc.1");
        version.setLatestVersion("4.0.0-rc.1");
        version.setHasUpdate(false);
        when(updateService.checkUpdate()).thenReturn(version);
        when(updateService.updateAgentStatus()).thenThrow(new IllegalStateException("agent timeout"));
        OperationsDiagnosticsService service = new OperationsDiagnosticsService(
                jdbc, mock(WebSocketService.class), updateService);
        UserContext.set(42L, "tenant-seven-owner", 7L);

        Map<String, Object> overview = service.overview(true);

        assertEquals("UNKNOWN", overview.get("overallStatus"));
        assertEquals(2L, overview.get("unknownCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) overview.get("checks");
        assertEquals("UNKNOWN", checks.stream()
                .filter(check -> "VERSION".equals(check.get("key")))
                .findFirst().orElseThrow().get("status"));
        assertEquals("UNKNOWN", checks.stream()
                .filter(check -> "UPDATE_AGENT".equals(check.get("key")))
                .findFirst().orElseThrow().get("status"));
    }

    private JdbcTemplate zeroCountJdbc(Long tenantId) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(tenantId))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(tenantId))).thenReturn(List.of());
        return jdbc;
    }
}
