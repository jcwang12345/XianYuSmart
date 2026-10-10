package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopProfileSyncServiceTest {

    private final AccountAccessService access = mock(AccountAccessService.class);
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final AccountService accountService = mock(AccountService.class);
    private final ShopProfileCollector collector = mock(ShopProfileCollector.class);
    private final ShopProfileParser parser = mock(ShopProfileParser.class);
    private final ShopProfilePersistenceService persistence = mock(ShopProfilePersistenceService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Executor direct = Runnable::run;
    private ShopProfileSyncService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        service = new ShopProfileSyncService(access, accounts, accountService, collector, parser,
                persistence, jdbc, direct);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void checksAccountScopeBeforeReadingAccountOrCallingPlatform() {
        doThrow(new com.xianyusmart.exception.BusinessException(403, "forbidden"))
                .when(access).requireAccess(9L);

        assertThrows(com.xianyusmart.exception.BusinessException.class,
                () -> service.start(9L, "request-1"));

        verifyNoInteractions(accounts, collector, persistence);
    }

    @Test
    void collectorFailureWritesFailureOnlyAndNeverParsesOrCompletesSnapshot() {
        XianyuAccount account = new XianyuAccount();
        account.setId(5L);
        account.setUnb("u5");
        when(accounts.selectById(5L)).thenReturn(account);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("cookie_status"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(accountService.getCookieByAccountId(5L)).thenReturn("cookie=value");
        when(collector.collect(5L, "u5", "cookie=value"))
                .thenReturn(ShopProfileCollector.CollectionResult.failed(
                        "VERIFICATION_REQUIRED", "需要验证", true));

        service.start(5L, "request-2");

        verify(persistence).createAttempt(5L, "request-2", "MANUAL");
        verify(persistence).markRunning(5L, "request-2");
        verify(persistence).completeFailure(eq(5L), eq("request-2"), isNull(),
                eq("VERIFICATION_REQUIRED"), eq("需要验证"), contains("扫码"));
        verifyNoInteractions(parser);
        verify(persistence, never()).completeSuccess(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void successfulReadParsesAndCommitsOnce() {
        XianyuAccount account = new XianyuAccount();
        account.setId(6L);
        account.setUnb("u6");
        when(accounts.selectById(6L)).thenReturn(account);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("cookie_status"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(accountService.getCookieByAccountId(6L)).thenReturn("cookie=value");
        ShopProfileCollector.CollectionResult collected = ShopProfileCollector.CollectionResult.success(
                "PLATFORM_API", "head", "nav", "items", false);
        when(collector.collect(6L, "u6", "cookie=value")).thenReturn(collected);
        ShopProfileParser realParser = new ShopProfileParser(new com.fasterxml.jackson.databind.ObjectMapper());
        ShopProfileParser.ParsedProfile profile = realParser.parse("u6",
                "{\"ret\":[\"SUCCESS\"],\"data\":{\"baseInfo\":{\"kcUserId\":\"u6\",\"tags\":{}},\"module\":{}}}",
                "{\"ret\":[\"SUCCESS\"],\"data\":{}}",
                "{\"ret\":[\"SUCCESS\"],\"data\":{}}");
        when(parser.parseMerged("u6", collected)).thenReturn(profile);

        service.start(6L, "request-3");

        verify(persistence).completeSuccess(6L, "request-3", "PLATFORM_API", profile);
        verify(persistence, never()).completeFailure(anyLong(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void expiredCredentialRecordsFailureWithoutCallingPlatformOrReplacingSnapshot() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("u7");
        when(accounts.selectById(7L)).thenReturn(account);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("cookie_status"), eq(Integer.class), any(Object[].class))).thenReturn(2);

        service.start(7L, "request-expired");

        verify(persistence).completeFailure(eq(7L), eq("request-expired"), eq("NONE"),
                eq("CREDENTIAL_EXPIRED"), contains("未发起平台读取"), contains("上次成功画像"));
        verifyNoInteractions(accountService, collector, parser);
        verify(persistence, never()).completeSuccess(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void concurrentSameRequestIsIdempotentAndDifferentRequestIsBusy() throws Exception {
        var attempts = new ConcurrentHashMap<String, Map<String, Object>>();
        var queued = new CopyOnWriteArrayList<Runnable>();
        Executor queueOnly = queued::add;
        ShopProfileSyncService concurrent = new ShopProfileSyncService(access, accounts, accountService, collector,
                parser, persistence, jdbc, queueOnly);
        XianyuAccount account = new XianyuAccount(); account.setId(11L); account.setUnb("u11");
        when(accounts.selectById(11L)).thenReturn(account);
        when(jdbc.queryForList(contains("request_id=?"), any(Object[].class))).thenAnswer(call -> {
            String request = call.getArgument(3, String.class);
            Map<String, Object> value = attempts.get(request);
            return value == null ? List.of() : List.of(new java.util.LinkedHashMap<>(value));
        });
        when(jdbc.queryForObject(contains("status IN ('QUEUED','RUNNING')"), eq(Integer.class), any(Object[].class)))
                .thenAnswer(call -> attempts.isEmpty() ? 0 : 1);
        doAnswer(call -> {
            String request = call.getArgument(1);
            attempts.put(request, Map.of("requestId", request, "status", "QUEUED", "source", "NONE"));
            return null;
        }).when(persistence).createAttempt(eq(11L), anyString(), eq("MANUAL"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> withTenant(() -> concurrent.start(11L, "same-request")));
            var second = pool.submit(() -> withTenant(() -> concurrent.start(11L, "same-request")));
            Map<String, Object> a = first.get(5, TimeUnit.SECONDS);
            Map<String, Object> b = second.get(5, TimeUnit.SECONDS);
            assertEquals("RUNNING", a.get("status")); assertEquals("RUNNING", b.get("status"));
            assertEquals(1, queued.size());
            verify(persistence, times(1)).createAttempt(11L, "same-request", "MANUAL");
            Map<String, Object> busy = concurrent.start(11L, "other-request");
            assertEquals("BUSY", busy.get("status"));
            attempts.clear();
            Map<String, Object> retry = concurrent.start(11L, "after-terminal");
            assertEquals("RUNNING", retry.get("status"));
            verify(persistence).createAttempt(11L, "after-terminal", "MANUAL");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentAccountsAcquireIndependentStartLocks() throws Exception {
        JdbcTemplate localJdbc = mock(JdbcTemplate.class);
        ShopProfilePersistenceService localPersistence = mock(ShopProfilePersistenceService.class);
        XianyuAccountMapper localAccounts = mock(XianyuAccountMapper.class);
        XianyuAccount a = new XianyuAccount(); a.setId(21L); a.setUnb("u21");
        XianyuAccount b = new XianyuAccount(); b.setId(22L); b.setUnb("u22");
        when(localAccounts.selectById(21L)).thenReturn(a); when(localAccounts.selectById(22L)).thenReturn(b);
        when(localJdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(localJdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(3, TimeUnit.SECONDS); return null; })
                .when(localPersistence).createAttempt(anyLong(), anyString(), eq("MANUAL"));
        ShopProfileSyncService concurrent = new ShopProfileSyncService(access, localAccounts, accountService,
                collector, parser, localPersistence, localJdbc, command -> {});
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> withTenant(() -> concurrent.start(21L, "r21")));
            var second = pool.submit(() -> withTenant(() -> concurrent.start(22L, "r22")));
            assertEquals("RUNNING", first.get(5, TimeUnit.SECONDS).get("status"));
            assertEquals("RUNNING", second.get(5, TimeUnit.SECONDS).get("status"));
        } finally { pool.shutdownNow(); }
    }

    private static <T> T withTenant(java.util.concurrent.Callable<T> action) throws Exception {
        TenantContext.set(1L);
        try { return action.call(); } finally { TenantContext.clear(); }
    }
}
