package com.xianyusmart.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEngagementServiceTest {
    @Test void dailyLimitStopsBeforeCreatingEvent() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(101L))).thenReturn(20);
        OrderEngagementService.Admission result = service(jdbc).beginInvite(
                1L, 101L, 9L, "QA-ORDER-9", 0, "请评价");
        assertEquals(OrderEngagementService.Decision.DAILY_LIMIT, result.decision());
        assertFalse(result.allowed());
        verify(jdbc, never()).update(anyString(), eq(1L), eq(101L), eq(9L), eq("QA-ORDER-9"),
                anyString(), anyString(), anyString());
    }

    @Test void unknownEventIsNeverAutomaticallyRetried() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of(Map.of(
                "id", 7L, "status", "UNKNOWN", "attempt_count", 1,
                "next_retry_time", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)))));
        OrderEngagementService.Admission result = service(jdbc).beginInvite(
                1L, 101L, 9L, "QA-ORDER-9", 0, "请评价");
        assertEquals(OrderEngagementService.Decision.RESULT_UNKNOWN, result.decision());
        assertFalse(result.allowed());
    }

    @Test void freshEventReceivesStableRequestAndCanSend() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(101L))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), anyString())).thenReturn(17L);
        OrderEngagementService.Admission result = service(jdbc).beginInvite(
                1L, 101L, 9L, "QA-ORDER-9", 2, "请评价");
        assertTrue(result.allowed());
        assertEquals(17L, result.eventId());
        assertEquals("invite-1-101-QA-ORDER-9-2", result.requestId());
    }

    private JdbcTemplate baseJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L))).thenReturn(101L);
        return jdbc;
    }

    private OrderEngagementService service(JdbcTemplate jdbc) {
        return new OrderEngagementService(jdbc, 20, 5);
    }
}
