package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

class AccountMatrixServiceTest {

    private XianyuAccountMapper accountMapper;
    private JdbcTemplate jdbcTemplate;
    private AccountAccessService accountAccessService;
    private OperationLogService operationLogService;
    private AccountMatrixService service;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        accountMapper = mock(XianyuAccountMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        operationLogService = mock(OperationLogService.class);
        service = new AccountMatrixService(accountMapper, jdbcTemplate, accountAccessService,
                operationLogService, new ObjectMapper());
        UserContext.set(31L, "tester", 9L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void unsyncedProfileAndRisksAreUnknownInsteadOfZero() {
        XianyuAccount account = account(5L, "测试店铺");
        when(accountMapper.selectList(any())).thenReturn(List.of(account));

        AccountMatrixService.MatrixPage page = service.listAccounts(null, null, null, 1, 20);

        assertEquals(1, page.total());
        Map<String, Object> row = page.records().getFirst();
        assertEquals("UNSYNCED", row.get("profileCoverageStatus"));
        assertEquals("UNSYNCED", row.get("riskCoverageStatus"));
        assertNull(row.get("knownActiveRiskCount"));
        assertNull(row.get("shopNickname"));
    }

    @Test
    void summaryKeepsRiskTotalUnknownWhenNoAccountHasCoverage() {
        when(accountMapper.selectList(any())).thenReturn(List.of(account(5L, "A"), account(6L, "B")));

        Map<String, Object> summary = service.summary();

        assertEquals("UNSYNCED", summary.get("riskCoverage"));
        assertNull(summary.get("knownActiveRiskCount"));
        assertEquals(2, summary.get("unsyncedProfileCount"));
    }

    @Test
    void emptyAccountScopeDoesNotClaimFullRiskCoverage() {
        when(accountMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> summary = service.summary();

        assertEquals(0, summary.get("accountCount"));
        assertEquals("UNSYNCED", summary.get("riskCoverage"));
        assertNull(summary.get("knownActiveRiskCount"));
    }

    @Test
    void detailChecksAccountScopeBeforeReadingAccount() {
        XianyuAccount account = account(5L, "A");
        when(accountMapper.selectById(5L)).thenReturn(account);

        service.accountDetail(5L);

        verify(accountAccessService, atLeastOnce()).requireAccess(5L);
    }

    @Test
    void profileValidationRejectsInvalidPositiveRateWithoutWriting() {
        when(accountMapper.selectById(5L)).thenReturn(account(5L, "A"));
        AccountMatrixService.ProfileSnapshotInput input = new AccountMatrixService.ProfileSnapshotInput(
                "req-1", "PLATFORM_WEB", "SUCCEEDED", "PARTIAL",
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("1.01"),
                null, null, null, null, null,
                null, null, null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.saveProfileSnapshot(5L, input));

        assertEquals(400, error.getCode());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void validProfileUsesExpectedSqlShapeAndKeepsNullMetrics() {
        when(accountMapper.selectById(5L)).thenReturn(account(5L, "A"));
        AccountMatrixService.ProfileSnapshotInput input = new AccountMatrixService.ProfileSnapshotInput(
                "req-valid", "MANUAL_IMPORT", "SUCCEEDED", "PARTIAL", "店铺名", null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        service.saveProfileSnapshot(5L, input);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeast(2)).update(sql.capture(), args.capture());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("INSERT INTO xianyu_shop_profile_snapshot")) {
                assertEquals(34, args.getAllValues().get(i).length);
                return;
            }
        }
        throw new AssertionError("未执行店铺画像插入");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void riskHandlingRequestIsIdempotent() {
        Map<String, Object> risk = new java.util.LinkedHashMap<>();
        risk.put("riskId", 12L);
        risk.put("accountId", 5L);
        risk.put("localHandlingStatus", "ACKNOWLEDGED");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("xianyu_shop_risk_event")
                        ? List.of(risk) : List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        Map<String, Object> result = service.updateRiskHandling(12L,
                new AccountMatrixService.RiskHandlingInput("request-duplicate", "DONE", "已复核"));

        assertTrue((Boolean) result.get("idempotentReplay"));
        verify(accountAccessService).requireAccess(5L);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void pageSizeIsCappedAndInvalidFilterIsRejected() {
        when(accountMapper.selectList(any())).thenReturn(List.of());
        assertEquals(100, service.listAccounts(null, null, null, 1, 500).pageSize());
        assertThrows(BusinessException.class,
                () -> service.listAccounts(null, "MAGIC", null, 1, 20));
    }

    private XianyuAccount account(Long id, String note) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setAccountNote(note);
        account.setUnb("unb-" + id);
        account.setStatus(1);
        return account;
    }
}
