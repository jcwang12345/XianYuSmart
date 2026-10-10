package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopRiskSyncServiceTest {

    private final ShopRiskSyncService service = new ShopRiskSyncService(
            null, null, null, null, null, null, new ObjectMapper(), null);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void parsesOfficialCroFieldsWithoutInventingAppealDeadline() throws Exception {
        long ended = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        String json = """
                {"ret":["SUCCESS::调用成功"],"data":{"data":{"idle_violation_list":[{
                  "id":20619174041,"title":"发布风险商品","rule_code":"rule-a",
                  "reason":"商品信息存在风险","punish_result":"立即下架商品",
                  "punish_time":1624347401000,"punish_end_time":%d,"point":0,
                  "is_appeal_front":1,"status":2
                }]},"has_next_page":false}}
                """.formatted(ended);

        ShopRiskSyncService.ParsedPage page = service.parse(json);

        assertFalse(page.hasNext());
        assertEquals(1, page.records().size());
        assertEquals("20619174041", page.records().get(0).platformId());
        assertEquals("rule-a", page.records().get(0).ruleCode());
        assertEquals("AVAILABLE", page.records().get(0).appealStatus());
        assertEquals("RESOLVED", page.records().get(0).status());
        assertEquals("WARNING", page.records().get(0).severity());
    }

    @Test
    void sameRequestReplayDoesNotReadCredentialOrCallPlatform() {
        TenantContext.set(1L);
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForList(contains("FROM xianyu_shop_risk_sync_attempt"),
                eq(1L), eq(9L), eq("same"))).thenReturn(List.of(Map.of(
                "requestId", "same", "status", "FAILED", "source", "PLATFORM_API",
                "coverageStatus", "UNSYNCED", "errorCode", "PLATFORM_AUTHORIZATION_REQUIRED")));

        Map<String, Object> replay = fixture.service.sync(9L, "same");

        assertEquals("FAILED", replay.get("status"));
        assertEquals(true, replay.get("idempotentReplay"));
        verifyNoInteractions(fixture.accountService, fixture.api);
    }

    @Test
    void differentRequestReturnsBusyBeforeCredentialOrPlatformRead() {
        TenantContext.set(1L);
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForList(anyString(), eq(1L), eq(9L), eq("second"))).thenReturn(List.of());
        when(fixture.jdbc.queryForObject(contains("status='RUNNING'"), eq(Integer.class), eq(1L), eq(9L)))
                .thenReturn(1);

        Map<String, Object> busy = fixture.service.sync(9L, "second");

        assertEquals("BUSY", busy.get("status"));
        verifyNoInteractions(fixture.accountService, fixture.api);
        verify(fixture.persistence, never()).createAttempt(anyLong(), anyString(), anyString());
    }

    @Test
    void concurrentRequestIsBusyAndOnlyOnePlatformCallRuns() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean created = new AtomicBoolean(false);
        CountDownLatch platformEntered = new CountDownLatch(1);
        CountDownLatch releasePlatform = new CountDownLatch(1);
        when(fixture.jdbc.queryForList(anyString(), anyLong(), eq(9L), anyString())).thenReturn(List.of());
        when(fixture.jdbc.queryForObject(contains("status='RUNNING'"), eq(Integer.class), anyLong(), eq(9L)))
                .thenAnswer(ignored -> created.get() ? 1 : 0);
        doAnswer(ignored -> { created.set(true); return true; })
                .when(fixture.persistence).createAttempt(eq(9L), eq("first"), eq("MANUAL"));
        when(fixture.accountService.getCookieByAccountId(9L)).thenReturn("cookie");
        when(fixture.api.callApiWithRetry(eq(9L), anyString(), anyString(), anyMap(), eq("cookie"), isNull(), isNull()))
                .thenAnswer(ignored -> {
                    platformEntered.countDown();
                    releasePlatform.await(3, TimeUnit.SECONDS);
                    return new XianyuApiCallUtils.ApiCallResult(false, null, "denied", false);
                });

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> withTenant(() -> fixture.service.sync(9L, "first")));
            platformEntered.await(3, TimeUnit.SECONDS);
            Map<String, Object> second = withTenant(() -> fixture.service.sync(9L, "second"));
            assertEquals("BUSY", second.get("status"));
            releasePlatform.countDown();
            assertEquals("FAILED", first.get(3, TimeUnit.SECONDS).get("status"));
            verify(fixture.api, times(1)).callApiWithRetry(eq(9L), anyString(), anyString(), anyMap(),
                    eq("cookie"), isNull(), isNull());
        } finally {
            releasePlatform.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void permissionFailureHappensBeforeAnyNetworkOrAttempt() {
        TenantContext.set(2L);
        Fixture fixture = fixture();
        doThrow(new BusinessException(403, "无权访问"))
                .when(fixture.access).requireAccess(9L);

        assertThrows(BusinessException.class, () -> fixture.service.sync(9L, "denied"));

        verifyNoInteractions(fixture.accountService, fixture.api, fixture.persistence);
    }

    @Test
    void missingCroApiReturnsExactReasonAndActionableRecoveryInsteadOfGenericAuthorizationCopy() {
        TenantContext.set(1L);
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForList(anyString(), eq(1L), eq(9L), eq("cro-missing"))).thenReturn(List.of());
        when(fixture.jdbc.queryForObject(contains("status='RUNNING'"), eq(Integer.class), eq(1L), eq(9L)))
                .thenReturn(0);
        when(fixture.accountService.getCookieByAccountId(9L)).thenReturn("cookie");
        when(fixture.api.callApiWithRetry(eq(9L), anyString(), anyString(), anyMap(), eq("cookie"), isNull(), isNull()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null,
                        "FAIL_SYS_API_NOT_FOUNDED::请求API不存在", false));

        Map<String, Object> failed = fixture.service.sync(9L, "cro-missing");

        assertEquals("FAILED", failed.get("status"));
        assertEquals("PLATFORM_API_NOT_AVAILABLE", failed.get("errorCode"));
        assertEquals(true, String.valueOf(failed.get("errorMessage")).contains("FAIL_SYS_API_NOT_FOUNDED"));
        assertEquals(true, String.valueOf(failed.get("errorMessage")).contains("尚未确定"));
        assertEquals(true, String.valueOf(failed.get("recoveryHint")).contains("不能证明"));
        assertEquals(false, String.valueOf(failed.get("recoveryHint")).contains("受邀服务商"));
        verify(fixture.persistence).completeFailure(eq(9L), eq("cro-missing"),
                eq("PLATFORM_API_NOT_AVAILABLE"), contains("请求 API 不存在"), contains("官方渠道确认"));
    }

    @Test
    void unknownBusinessRefusalDoesNotInventPermissionFailureOrExposeRawDetails() {
        TenantContext.set(1L);
        Fixture fixture = fixture();
        when(fixture.jdbc.queryForList(anyString(), eq(1L), eq(9L), eq("unknown-refusal"))).thenReturn(List.of());
        when(fixture.jdbc.queryForObject(contains("status='RUNNING'"), eq(Integer.class), eq(1L), eq(9L))).thenReturn(0);
        when(fixture.accountService.getCookieByAccountId(9L)).thenReturn("cookie");
        when(fixture.api.callApiWithRetry(eq(9L), anyString(), anyString(), anyMap(), eq("cookie"), isNull(), isNull()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null,
                        "FAIL_BIZ_FORBIDDEN::cookie=private-test-value", false));

        Map<String, Object> failed = fixture.service.sync(9L, "unknown-refusal");

        assertEquals("PLATFORM_REQUEST_REJECTED", failed.get("errorCode"));
        assertEquals(true, String.valueOf(failed.get("errorMessage")).contains("尚未核实"));
        assertFalse(String.valueOf(failed.get("errorMessage")).contains("private-test-value"));
        assertFalse(String.valueOf(failed.get("recoveryHint")).contains("private-test-value"));
        verify(fixture.persistence).completeFailure(eq(9L), eq("unknown-refusal"),
                eq("PLATFORM_REQUEST_REJECTED"), contains("尚未核实"), contains("不要将未知拒绝当作缺少权限"));
    }

    private Fixture fixture() {
        AccountAccessService access = mock(AccountAccessService.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        AccountService accountService = mock(AccountService.class);
        XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
        AccountMatrixService matrix = mock(AccountMatrixService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopRiskSyncPersistenceService persistence = mock(ShopRiskSyncPersistenceService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(9L);
        account.setUnb("39175043");
        when(accountMapper.selectById(9L)).thenReturn(account);
        when(persistence.createAttempt(anyLong(), anyString(), anyString())).thenReturn(true);
        return new Fixture(new ShopRiskSyncService(access, accountMapper, accountService, api, matrix,
                jdbc, new ObjectMapper(), persistence), access, accountService, api, jdbc, persistence);
    }

    private Map<String, Object> withTenant(ThrowingSupplier action) throws Exception {
        TenantContext.set(1L);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private record Fixture(ShopRiskSyncService service, AccountAccessService access,
                           AccountService accountService, XianyuApiCallUtils api,
                           JdbcTemplate jdbc, ShopRiskSyncPersistenceService persistence) {}

    @FunctionalInterface
    private interface ThrowingSupplier {
        Map<String, Object> get() throws Exception;
    }
}
