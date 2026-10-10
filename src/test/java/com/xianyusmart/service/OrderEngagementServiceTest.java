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
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(101L), eq("REVIEW_INVITE"))).thenReturn(20);
        OrderEngagementService.Admission result = service(jdbc).beginInvite(
                1L, 101L, 9L, "QA-ORDER-9", 0, "请评价");
        assertEquals(OrderEngagementService.Decision.DAILY_LIMIT, result.decision());
        assertFalse(result.allowed());
        verify(jdbc, never()).update(anyString(), eq(1L), eq(101L), eq(9L), eq("QA-ORDER-9"),
                anyString(), anyString(), anyString(), anyString());
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
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(101L), eq("REVIEW_INVITE"))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), anyString())).thenReturn(17L);
        OrderEngagementService.Admission result = service(jdbc).beginInvite(
                1L, 101L, 9L, "QA-ORDER-9", 2, "请评价");
        assertTrue(result.allowed());
        assertEquals(17L, result.eventId());
        assertEquals("invite-1-101-QA-ORDER-9-2", result.requestId());
    }

    @Test void autoRateUsesIndependentDailyQuotaAndStableKey() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(101L), eq("AUTO_RATE"))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), anyString())).thenReturn(18L);
        var admission = service(jdbc).beginAutoRate(1L, 101L, 9L, "QA-ORDER-9", "感谢支持");
        assertTrue(admission.allowed());
        assertEquals("rate-1-101-QA-ORDER-9", admission.requestId());
        verify(jdbc).update(anyString(), eq(1L), eq(101L), eq(9L), eq("QA-ORDER-9"),
                eq("AUTO_RATE"), eq("AUTO_RATE:101:QA-ORDER-9"), eq(admission.requestId()), anyString());
    }

    @Test void autoRateUnknownProcessingOrManualNeverResends() {
        for (String status : List.of("UNKNOWN", "PROCESSING", "MANUAL_REQUIRED")) {
            JdbcTemplate jdbc = baseJdbc();
            when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of(Map.of(
                    "id", 7L, "status", status, "attempt_count", 1)));
            var admission = service(jdbc).beginAutoRate(1L, 101L, 9L, "QA-ORDER-9", "感谢支持");
            assertEquals(OrderEngagementService.Decision.RESULT_UNKNOWN, admission.decision());
            verify(jdbc, never()).update(anyString(), eq(1L), eq(101L), eq(9L), eq("QA-ORDER-9"),
                    anyString(), anyString(), anyString(), anyString());
        }
    }

    @Test void autoRateSuccessIsNotResubmitted() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of(Map.of(
                "id", 7L, "status", "SUCCESS", "attempt_count", 1)));
        assertEquals(OrderEngagementService.Decision.ALREADY_CONFIRMED,
                service(jdbc).beginAutoRate(1L, 101L, 9L, "QA-ORDER-9", "感谢支持").decision());
    }

    @Test void autoRateKnownFailureResumesOnlyAfterRetryTime() {
        JdbcTemplate jdbc = baseJdbc();
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of(Map.of(
                "id", 7L, "status", "FAILED", "attempt_count", 1,
                "next_retry_time", Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)))));
        assertEquals(OrderEngagementService.Decision.RETRY_LATER,
                service(jdbc).beginAutoRate(1L, 101L, 9L, "QA-ORDER-9", "感谢支持").decision());
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
