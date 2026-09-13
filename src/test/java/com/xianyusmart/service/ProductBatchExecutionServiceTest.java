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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductBatchExecutionServiceTest {

    @Test
    void platformTimeoutBecomesUnknownAndIsNotBlindlyRetried() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformPublishService platform = mock(PlatformPublishService.class);
        ItemDetailSyncService sync = mock(ItemDetailSyncService.class);
        GoodsAutomationService automation = mock(GoodsAutomationService.class);
        ProductBatchExecutionService service = new ProductBatchExecutionService(
                jdbc, platform, sync, automation, new ObjectMapper());
        Map<String, Object> job = job();
        Map<String, Object> item = item("OFF_SHELF");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(item));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
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
                jdbc, platform, mock(ItemDetailSyncService.class), mock(GoodsAutomationService.class), new ObjectMapper());
        Map<String, Object> job = job();
        Map<String, Object> item = item("OFF_SHELF");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(item));
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

    private Map<String, Object> job() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("tenant_id", 9L);
        job.put("id", 10L);
        job.put("request_id", "batch-request");
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
