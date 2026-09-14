package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductBatchExecutionServiceTest {

    @Test
    void restartRecoveryMarksInFlightItemsUnknownAndRequeuesRemainingWork() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(2, 1);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, mock(PlatformPublishService.class), mock(ItemDetailSyncService.class),
                mock(GoodsAutomationService.class), new ObjectMapper(), mock(NotificationCenterService.class));

        service.recoverInterruptedWork();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).update(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("status='UNKNOWN'")
                && value.contains("WORKER_RESTART")));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("status='QUEUED'")
                && value.contains("recovery_count=recovery_count+1")));
    }

    @Test
    void cancellationOnlyCancelsQueuedItemsAndFinishesPersistedJob() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, mock(PlatformPublishService.class), mock(ItemDetailSyncService.class),
                mock(GoodsAutomationService.class), new ObjectMapper(), mock(NotificationCenterService.class));
        Map<String, Object> job = job();
        job.put("status", "CANCEL_REQUESTED");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "total", 2L, "succeeded", 0L, "failed", 0L, "unknown_count", 0L,
                "active", 0L, "skipped", 0L, "cancelled", 2L, "conflicts", 0L));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class), any(Object[].class)))
                .thenReturn("CANCEL_REQUESTED");

        service.executeJob(job);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(1)).update(sql.capture(), args.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("status='CANCELLED'")
                && value.contains("status='QUEUED'")));
        boolean cancelledJob = false;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("success_count=?")
                    && "CANCELLED".equals(args.getAllValues().get(i)[0])) cancelledJob = true;
        }
        assertTrue(cancelledJob);
    }

    @Test
    void platformTimeoutBecomesUnknownAndIsNotBlindlyRetried() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformPublishService platform = mock(PlatformPublishService.class);
        ItemDetailSyncService sync = mock(ItemDetailSyncService.class);
        GoodsAutomationService automation = mock(GoodsAutomationService.class);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, platform, sync, automation, new ObjectMapper(), mock(NotificationCenterService.class));
        Map<String, Object> job = job();
        Map<String, Object> item = item("OFF_SHELF");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(item));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(platform.changeListingStatus(2L, "goods-1", false)).thenThrow(new IllegalStateException("平台请求超时，结果无法确认"));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "total", 1L, "succeeded", 0L, "failed", 0L, "unknown_count", 1L, "active", 0L, "conflicts", 0L));

        service.executeJob(job);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(1)).update(sql.capture(), args.capture());
        boolean unknownPersisted = false;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("SET status=?, outcome_state=?")
                    && "UNKNOWN".equals(args.getAllValues().get(i)[0])
                    && "UNKNOWN".equals(args.getAllValues().get(i)[1])
                    && args.getAllValues().get(i)[3] == null) {
                unknownPersisted = true;
            }
        }
        assertTrue(unknownPersisted);
    }

    @Test
    void platformSuccessWithLocalSyncFailureUsesDistinctOutcome() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformPublishService platform = mock(PlatformPublishService.class);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, platform, mock(ItemDetailSyncService.class), mock(GoodsAutomationService.class), new ObjectMapper(),
                mock(NotificationCenterService.class));
        Map<String, Object> job = job();
        Map<String, Object> item = item("OFF_SHELF");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(item));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(platform.changeListingStatus(2L, "goods-1", false)).thenReturn(Map.of("success", true));
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("UPDATE xianyu_goods SET") ? 0 : 1);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "total", 1L, "succeeded", 1L, "failed", 0L, "unknown_count", 0L, "active", 0L, "conflicts", 0L));

        service.executeJob(job);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(1)).update(sql.capture(), args.capture());
        boolean pendingRepair = false;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("SET status='SUCCEEDED', outcome_state=?")
                    && "PLATFORM_CONFIRMED_LOCAL_PENDING".equals(args.getAllValues().get(i)[0])) {
                pendingRepair = true;
            }
        }
        assertTrue(pendingRepair);
    }

    @Test
    void revokedPermissionSkipsQueuedItemBeforePlatformWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformPublishService platform = mock(PlatformPublishService.class);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, platform, mock(ItemDetailSyncService.class), mock(GoodsAutomationService.class),
                new ObjectMapper(), mock(NotificationCenterService.class));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(item("OFF_SHELF")));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "total", 1L, "succeeded", 0L, "failed", 0L, "unknown_count", 0L,
                "active", 0L, "skipped", 1L, "cancelled", 0L, "conflicts", 0L));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class), any(Object[].class)))
                .thenReturn("RUNNING");

        service.executeJob(job());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(1)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("AUTHORIZATION_REVOKED")
                && value.contains("status='SKIPPED'")));
        verify(platform, never()).changeListingStatus(any(), anyString(), anyBoolean());
    }

    private Map<String, Object> job() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("tenant_id", 9L);
        job.put("id", 10L);
        job.put("request_id", "batch-request");
        job.put("idempotency_key", "batch-request");
        job.put("max_operations_per_minute", 10);
        job.put("operator_user_id", 4L);
        job.put("operator_username", "tester");
        return job;
    }

    private Map<String, Object> item(String operation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 11L);
        item.put("xianyu_account_id", 2L);
        item.put("xy_goods_id", "goods-1");
        item.put("operation_type", operation);
        item.put("attempt_count", 0);
        item.put("max_attempts", 3);
        return item;
    }
}
