package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsOrder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomationActivationServiceTest {
    @Test void newOrderNeedsBothLocalAndPlatformCreationAfterStableBoundary() {
        JdbcTemplate jdbc = jdbc("2026-10-09T14:00:00.123|232");
        assertTrue(new AutomationActivationService(jdbc).allowsOrder("AUTO_RATE", order()));
        var before = order(); before.setOrderCreateTime("2026-10-08 13:00:00");
        assertFalse(new AutomationActivationService(jdbc).allowsOrder("AUTO_RATE", before));
        before = order(); before.setCreateTime("2026-10-08 13:00:00");
        assertFalse(new AutomationActivationService(jdbc).allowsOrder("AUTO_RATE", before));
        before = order(); before.setId(232L);
        assertFalse(new AutomationActivationService(jdbc).allowsOrder("AUTO_RATE", before));
    }

    @Test void missingInvalidOrAmbiguousTimeAndBoundaryFailClosed() {
        for (String boundary : List.of("", "invalid|232", "2026-10-09T14:00|-1", "2026-10-09T14:00|x")) {
            assertFalse(new AutomationActivationService(jdbc(boundary)).allowsOrder("REVIEW_INVITE", order()));
        }
        var service = new AutomationActivationService(jdbc("2026-10-09T14:00:00.123|232"));
        for (String bad : List.of("", "未知", "2026-02-30 14:00:01", "2026-10-09 14:00:00.123")) {
            var order = order(); order.setOrderCreateTime(bad);
            assertFalse(service.allowsOrder("REVIEW_INVITE", order));
        }
        var order = order(); order.setOrderCreateTime(null);
        assertFalse(service.allowsOrder("REVIEW_INVITE", order));
    }

    @Test void missingBaselineDoesNotGetInitializedByCompletedOrder() {
        var jdbc = jdbc(null);
        assertFalse(new AutomationActivationService(jdbc).allowsOrder("REVIEW_INVITE", order()));
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test void enrollmentUsesDatabaseClockAndWatermarkAndCannotResetOnLaterSaves() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L))).thenReturn(101L);
        when(jdbc.queryForList(anyString(), eq(1L), anyString())).thenReturn(List.of(), List.of(Map.of("setting_value", "existing")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L), eq("QA-GOODS"))).thenReturn(232L);
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.valueOf("2026-10-09 14:00:00.123"));
        var service = new AutomationActivationService(jdbc);
        service.ensureActivated("AUTO_RATE", 1L, 101L, "QA-GOODS");
        service.ensureActivated("AUTO_RATE", 1L, 101L, "QA-GOODS");
        verify(jdbc, times(1)).update(anyString(), eq(1L), eq("engagement_since:AUTO_RATE:101:QA-GOODS"),
                eq("2026-10-09T14:00:00.123|232"));
    }

    @Test void initializeEnabledEnrollsBothKindsBeforeAnyOrderAndPreservesFirstObservation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Map<String, Object>> enabled = List.of(
                Map.of("tenant_id", 1L, "xianyu_account_id", 101L, "xy_goods_id", "QA-GOODS", "kind", "AUTO_RATE"),
                Map.of("tenant_id", 1L, "xianyu_account_id", 101L, "xy_goods_id", "QA-GOODS", "kind", "REVIEW_INVITE"));
        when(jdbc.queryForList(contains("UNION"))).thenReturn(enabled);
        when(jdbc.queryForObject(contains("FROM xianyu_account"), eq(Long.class), eq(1L), eq(101L)))
                .thenReturn(101L);
        Map<String, String> markers = new LinkedHashMap<>();
        when(jdbc.queryForList(contains("FROM xianyu_sys_setting"), eq(1L), anyString()))
                .thenAnswer(invocation -> {
                    String marker = markers.get(invocation.getArgument(2, String.class));
                    return marker == null ? List.of() : List.of(Map.of("setting_value", marker));
                });
        when(jdbc.queryForObject(contains("MAX(id)"), eq(Long.class), eq(1L), eq(101L), eq("QA-GOODS")))
                .thenReturn(0L);
        when(jdbc.queryForObject(eq("SELECT CURRENT_TIMESTAMP(3)"), eq(Timestamp.class)))
                .thenReturn(Timestamp.valueOf("2026-10-09 14:00:00.123"));
        when(jdbc.update(contains("INSERT INTO xianyu_sys_setting"), eq(1L), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    markers.put(invocation.getArgument(2, String.class), invocation.getArgument(3, String.class));
                    return 1;
                });
        var service = new AutomationActivationService(jdbc);

        service.initializeEnabled();
        service.initializeEnabled();

        assertEquals(Map.of("engagement_since:AUTO_RATE:101:QA-GOODS", "2026-10-09T14:00:00.123|0",
                "engagement_since:REVIEW_INVITE:101:QA-GOODS", "2026-10-09T14:00:00.123|0"), markers);
        verify(jdbc, times(2)).queryForList(contains("UNION"));
        verify(jdbc, times(2)).update(contains("INSERT INTO xianyu_sys_setting"), eq(1L), anyString(),
                eq("2026-10-09T14:00:00.123|0"));
        verify(jdbc, times(2)).queryForObject(eq("SELECT CURRENT_TIMESTAMP(3)"), eq(Timestamp.class));
        verify(jdbc, never()).queryForList(anyString(), eq(String.class), anyLong(), anyString());
    }

    @Test void existingMarkerIsReadWithCurrentReadLockAfterAccountLockAndNeverReplaced() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L))).thenReturn(101L);
        when(jdbc.queryForList(anyString(), eq(1L), eq("engagement_since:AUTO_RATE:101:QA-GOODS")))
                .thenReturn(List.of(Map.of("setting_value", "2026-10-09T14:00:00.123|232")));

        new AutomationActivationService(jdbc).ensureActivated("AUTO_RATE", 1L, 101L, "QA-GOODS");

        var calls = inOrder(jdbc);
        calls.verify(jdbc).queryForObject(
                eq("SELECT id FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE"),
                eq(Long.class), eq(1L), eq(101L));
        calls.verify(jdbc).queryForList(
                eq("SELECT setting_value FROM xianyu_sys_setting WHERE tenant_id=? AND setting_key=? FOR UPDATE"),
                eq(1L), eq("engagement_since:AUTO_RATE:101:QA-GOODS"));
        calls.verifyNoMoreInteractions();
    }

    @Test void orderTimesAcceptTSeparatorSecondsAndDatabaseFractionalSeconds() {
        var service = new AutomationActivationService(jdbc("2026-10-09T14:00:00.123|232"));
        for (String value : List.of("2026-10-09 14:00:01", "2026-10-09T14:00:01",
                "2026-10-09 14:00:01.0", "2026-10-09 14:00:01.123", "2026-10-09T14:00:01.123")) {
            var order = order();
            order.setCreateTime(value);
            order.setOrderCreateTime(value);
            assertTrue(service.allowsOrder("AUTO_RATE", order), value);
        }
    }

    @Test void eitherOrderTimeAtExactMillisecondBoundaryIsRejectedWithoutRounding() {
        var service = new AutomationActivationService(jdbc("2026-10-09T14:00:00.123|232"));
        var order = order();
        order.setCreateTime("2026-10-09 14:00:00.124");
        order.setOrderCreateTime("2026-10-09T14:00:00.124");
        assertTrue(service.allowsOrder("REVIEW_INVITE", order));
        order.setCreateTime("2026-10-09 14:00:00.123");
        assertFalse(service.allowsOrder("REVIEW_INVITE", order));
        order.setCreateTime("2026-10-09 14:00:00.124");
        order.setOrderCreateTime("2026-10-09T14:00:00.123");
        assertFalse(service.allowsOrder("REVIEW_INVITE", order));
        order.setCreateTime("2026-10-09 14:00:00.123");
        assertFalse(service.allowsOrder("REVIEW_INVITE", order));
    }

    @Test void rawSqlIsTenantScopedAndKindsAndProductKeysAreNotArbitrary() {
        var jdbc = jdbc("2026-10-09T14:00:00.123|232");
        var service = new AutomationActivationService(jdbc);
        service.allowsOrder("AUTO_RATE", order());
        verify(jdbc).queryForList(contains("tenant_id=? AND setting_key=?"), eq(String.class),
                eq(1L), eq("engagement_since:AUTO_RATE:101:QA-GOODS"));
        assertThrows(IllegalArgumentException.class, () -> service.allowsOrder("DELETE", order()));
        var order = order(); order.setXyGoodsId("../secret");
        assertThrows(IllegalArgumentException.class, () -> service.allowsOrder("AUTO_RATE", order));
    }

    private JdbcTemplate jdbc(String boundary) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(1L), anyString()))
                .thenReturn(boundary == null ? List.of() : List.of(boundary));
        return jdbc;
    }

    private XianyuGoodsOrder order() {
        var order = new XianyuGoodsOrder();
        order.setId(233L); order.setTenantId(1L); order.setXianyuAccountId(101L); order.setXyGoodsId("QA-GOODS");
        order.setCreateTime("2026-10-09 14:00:01"); order.setOrderCreateTime("2026-10-09 14:00:01");
        return order;
    }
}
