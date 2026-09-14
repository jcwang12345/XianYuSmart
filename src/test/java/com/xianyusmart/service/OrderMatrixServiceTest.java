package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderMatrixServiceTest {

    private JdbcTemplate jdbcTemplate;
    private NamedParameterJdbcTemplate namedJdbc;
    private AccountAccessService accountAccessService;
    private OrderMatrixService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        namedJdbc = mock(NamedParameterJdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        service = new OrderMatrixService(jdbcTemplate, namedJdbc, accountAccessService,
                mock(OperationLogService.class), new ObjectMapper().findAndRegisterModules());
        TenantContext.set(5L);
        UserContext.set(2L, "order-tester", 5L);
        when(namedJdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(namedJdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(namedJdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    ResultSetExtractor extractor = invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("FROM xianyu_account account")) {
                        when(rs.next()).thenReturn(true);
                        when(rs.getInt(anyString())).thenReturn(0);
                    } else {
                        when(rs.next()).thenReturn(false);
                    }
                    return extractor.extractData(rs);
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        UserContext.clear();
        AccountScopeContext.clear();
    }

    @Test
    void emptyOrdersKeepUnknownMoneyAndRefundMetricsNull() {
        Map<String, Object> result = service.list(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(0, summary.get("orderCount"));
        assertNull(summary.get("gmv"));
        assertNull(summary.get("refundAmount"));
        assertNull(summary.get("refundRate"));
        assertEquals("UNSYNCED", summary.get("coverageStatus"));
    }

    @Test
    void invalidDateWindowIsRejectedBeforeQuery() {
        OrderMatrixService.OrderFilter filter = new OrderMatrixService.OrderFilter(
                null, List.of(), null, null, "ALL",
                LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), 1, 20);

        BusinessException error = assertThrows(BusinessException.class, () -> service.list(filter));
        assertEquals(400, error.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refundApprovalIsExplicitlyUnavailableWhenNoReliablePlatformApiExists() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(refund()));
        OrderMatrixService.RefundActionCommand command = new OrderMatrixService.RefundActionCommand(
                "refund-request-1", "APPROVE", "同意", null);

        Map<String, Object> preview = service.refundActionPreview(3L, command);

        assertEquals(false, preview.get("available"));
        assertTrue(String.valueOf(preview.get("unavailableReason")).contains("不提供假执行按钮"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.executeRefundAction(3L, command));
        assertEquals(409, error.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void physicalShipmentCannotBeRecordedWithoutPlatformConfirmationEvidence() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(order()));
        OrderMatrixService.ShipmentCommand command = new OrderMatrixService.ShipmentCommand(
                "shipment-request-1", "SF", "顺丰", "SF123", false, "anything");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recordPhysicalShipment(8L, command));
        assertEquals(409, error.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnShipmentPreviewMakesTheManualPlatformEvidenceBoundaryExplicit() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(refund()));
        OrderMatrixService.ReturnShipmentCommand command = new OrderMatrixService.ReturnShipmentCommand(
                "return-request-1", "BUYER_TO_SELLER", "SF", "顺丰", "SF123456",
                "IN_TRANSIT", "买家已寄出", Instant.parse("2026-09-14T08:00:00Z"), null,
                false, null);

        Map<String, Object> preview = service.returnShipmentPreview(3L, command);

        assertEquals("NOT_PERFORMED", preview.get("platformWrite"));
        assertEquals("买家退回", preview.get("directionLabel"));
        assertTrue(String.valueOf(preview.get("confirmationText")).contains("本系统仅保存事实"));
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.recordReturnShipment(3L, command));
        assertEquals(409, denied.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnShipmentRejectsInvalidDirectionAndImpossibleTimeOrder() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(refund()));
        OrderMatrixService.ReturnShipmentCommand invalidDirection = new OrderMatrixService.ReturnShipmentCommand(
                "return-request-2", "WAREHOUSE_TO_PLATFORM", null, "顺丰", "SF123456",
                "IN_TRANSIT", null, null, null, true, null);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.returnShipmentPreview(3L, invalidDirection)).getCode());

        OrderMatrixService.ReturnShipmentCommand impossibleTime = new OrderMatrixService.ReturnShipmentCommand(
                "return-request-3", "SELLER_TO_BUYER", null, "中通", "ZT123456",
                "RECEIVED", null, Instant.parse("2026-09-14T09:00:00Z"),
                Instant.parse("2026-09-14T08:00:00Z"), true, null);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.returnShipmentPreview(3L, impossibleTime)).getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void confirmedReturnShipmentIsPersistedWithIdempotentEvidenceAndNoPlatformWriteClaim() {
        AtomicBoolean inserted = new AtomicBoolean(false);
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("returnShipmentId", 31L);
        shipment.put("refundCaseId", 3L);
        shipment.put("direction", "BUYER_TO_SELLER");
        shipment.put("trackingNumber", "SF123456");
        shipment.put("shipmentStatus", "IN_TRANSIT");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("FROM xianyu_refund_case")) return List.of(refund());
                    if (sql.contains("FROM xianyu_return_shipment")) {
                        return inserted.get() ? List.of(shipment) : List.of();
                    }
                    return List.of();
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO xianyu_return_shipment")) inserted.set(true);
            return 1;
        });
        OrderMatrixService.ReturnShipmentCommand draft = new OrderMatrixService.ReturnShipmentCommand(
                "return-request-4", "BUYER_TO_SELLER", "SF", "顺丰", "SF123456",
                "IN_TRANSIT", "买家已寄出", Instant.parse("2026-09-14T08:00:00Z"), null,
                true, null);
        String confirmation = String.valueOf(service.returnShipmentPreview(3L, draft).get("confirmationText"));
        OrderMatrixService.ReturnShipmentCommand confirmed = new OrderMatrixService.ReturnShipmentCommand(
                draft.requestId(), draft.direction(), draft.logisticsCompanyCode(), draft.logisticsCompanyName(),
                draft.trackingNumber(), draft.shipmentStatus(), draft.latestEvent(), draft.shippedTime(),
                draft.receivedTime(), true, confirmation);

        Map<String, Object> result = service.recordReturnShipment(3L, confirmed);

        assertEquals(false, result.get("idempotentReplay"));
        assertEquals("NOT_PERFORMED", result.get("platformWrite"));
        assertEquals("MANUAL_PLATFORM_CONFIRMED", result.get("evidenceState"));
        assertEquals(shipment, result.get("shipment"));
    }

    private Map<String, Object> order() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderRecordId", 8L);
        order.put("accountId", 9L);
        order.put("orderId", "O-1");
        order.put("buyerName", "测试买家");
        order.put("sessionId", "S-1");
        return order;
    }

    private Map<String, Object> refund() {
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("refundCaseId", 3L);
        refund.put("accountId", 9L);
        refund.put("orderRecordId", 8L);
        refund.put("orderId", "O-1");
        refund.put("buyerName", "测试买家");
        refund.put("requestedAmount", null);
        return refund;
    }
}
