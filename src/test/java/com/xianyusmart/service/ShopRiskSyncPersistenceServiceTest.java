package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopRiskSyncPersistenceServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createAttemptLocksTenantAccountBeforeCheckingAndCreatingRunningAttempt() {
        TenantContext.set(3L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT id FROM xianyu_account"), eq(Long.class), eq(3L), eq(9L)))
                .thenReturn(9L);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM xianyu_shop_risk_sync_attempt"),
                eq(Integer.class), eq(3L), eq(9L))).thenReturn(0);
        ShopRiskSyncPersistenceService persistence = new ShopRiskSyncPersistenceService(
                jdbc, mock(OperationLogService.class), new ObjectMapper());

        assertEquals(true, persistence.createAttempt(9L, "risk-r0", "MANUAL"));

        verify(jdbc).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(3L), eq(9L));
        verify(jdbc).update(contains("INSERT INTO xianyu_shop_risk_sync_attempt"),
                eq(3L), eq(9L), eq("risk-r0"), eq("MANUAL"));
        verify(jdbc).update(contains("INSERT INTO xianyu_account_dataset_state"),
                eq(3L), eq(9L), eq("risk-r0"));
    }

    @Test
    void createAttemptReturnsBusyAfterAccountLockWhenAnotherRequestIsRunning() {
        TenantContext.set(3L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT id FROM xianyu_account"), eq(Long.class), eq(3L), eq(9L)))
                .thenReturn(9L);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM xianyu_shop_risk_sync_attempt"),
                eq(Integer.class), eq(3L), eq(9L))).thenReturn(1);
        ShopRiskSyncPersistenceService persistence = new ShopRiskSyncPersistenceService(
                jdbc, mock(OperationLogService.class), new ObjectMapper());

        assertEquals(false, persistence.createAttempt(9L, "risk-r-busy", "MANUAL"));

        verify(jdbc).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq(3L), eq(9L));
        verify(jdbc, never()).update(contains("INSERT INTO xianyu_shop_risk_sync_attempt"),
                eq(3L), eq(9L), eq("risk-r-busy"), eq("MANUAL"));
        verify(jdbc, never()).update(contains("INSERT INTO xianyu_account_dataset_state"),
                eq(3L), eq(9L), eq("risk-r-busy"));
    }

    @Test
    void failureIsBoundToCurrentRequestAndWritesRequiredAudit() {
        TenantContext.set(3L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OperationLogService logs = mock(OperationLogService.class);
        ShopRiskSyncPersistenceService persistence = new ShopRiskSyncPersistenceService(
                jdbc, logs, new ObjectMapper());

        persistence.completeFailure(9L, "risk-r1", "PLATFORM_AUTHORIZATION_REQUIRED", "未授权", "申请授权");

        verify(jdbc).update(contains("AND request_id=? AND status='RUNNING'"),
                eq("PLATFORM_AUTHORIZATION_REQUIRED"), eq("未授权"), eq("申请授权"),
                eq(3L), eq(9L), eq("risk-r1"));
        verify(jdbc).update(contains("dataset_code='SHOP_RISKS' AND request_id=?"),
                eq("PLATFORM_AUTHORIZATION_REQUIRED"), eq("未授权"), eq("申请授权"),
                eq(3L), eq(9L), eq("risk-r1"));
        ArgumentCaptor<XianyuOperationLog> audit = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(logs).logRequired(audit.capture());
        assertEquals("RISK_SNAPSHOT_SYNC", audit.getValue().getOperationType());
        assertEquals("risk-r1", audit.getValue().getRequestId());
        assertEquals("risk-r1", audit.getValue().getIdempotencyKey());
        assertEquals("PLATFORM_FAILED", audit.getValue().getOutcomeState());
    }

    @Test
    void successRecordsCountsAndAuditOnce() {
        TenantContext.set(3L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OperationLogService logs = mock(OperationLogService.class);
        ShopRiskSyncPersistenceService persistence = new ShopRiskSyncPersistenceService(
                jdbc, logs, new ObjectMapper());

        persistence.completeSuccess(9L, "risk-r2", "FULL", 7, 1, Instant.parse("2026-09-17T00:00:00Z"));

        verify(jdbc).update(contains("record_count=?,page_count=?"),
                eq("FULL"), eq(7), eq(1), eq(3L), eq(9L), eq("risk-r2"));
        ArgumentCaptor<XianyuOperationLog> audit = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(logs).logRequired(audit.capture());
        assertEquals(1, audit.getValue().getOperationStatus());
        assertEquals("PLATFORM_CONFIRMED", audit.getValue().getOutcomeState());
    }
}
