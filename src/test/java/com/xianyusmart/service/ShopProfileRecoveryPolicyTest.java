package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopProfileRecoveryPolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void stableFailureClassesUseThirtyMinuteTwoHourAndTwelveHourBackoff() {
        ShopProfilePersistenceService persistence = new ShopProfilePersistenceService(mock(JdbcTemplate.class),
                new ObjectMapper(), mock(OperationLogService.class), clock);
        assertEquals(clock.instant().plusSeconds(30 * 60), persistence.retryAfterFor("UNAVAILABLE"));
        assertEquals(clock.instant().plusSeconds(2 * 60 * 60), persistence.retryAfterFor("RATE_LIMITED"));
        assertEquals(clock.instant().plusSeconds(12 * 60 * 60), persistence.retryAfterFor("CREDENTIAL_EXPIRED"));
        assertEquals(clock.instant().plusSeconds(12 * 60 * 60), persistence.retryAfterFor("VERIFICATION_REQUIRED"));
    }

    @Test
    void restartReconciliationClosesOldQueuedAndRunningAttemptsAsTimeout() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProfileSyncService service = new ShopProfileSyncService(mock(AccountAccessService.class),
                mock(XianyuAccountMapper.class), mock(AccountService.class), mock(ShopProfileCollector.class),
                mock(ShopProfileParser.class), mock(ShopProfilePersistenceService.class), jdbc,
                (Executor) Runnable::run, clock);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        service.reconcileInterruptedAttempts();

        verify(jdbc).update(contains("status IN ('QUEUED','RUNNING')"), args.capture());
        assertEquals(Timestamp.from(clock.instant().minusSeconds(10 * 60)), args.getValue()[0]);
        verify(jdbc).update(contains("last_error_code='TIMEOUT'"));
    }

    @Test
    void schedulerUsesControllableClockAndSelectsOnlyReadyCredentials() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskScheduler tasks = mock(TaskScheduler.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("tenantId", 1L, "accountId", 1L)));
        ShopProfileSyncScheduler scheduler = new ShopProfileSyncScheduler(jdbc, mock(ShopProfileSyncService.class),
                tasks, new BackgroundPlatformActivityPolicy(), clock);

        scheduler.scheduleEligibleAccounts();

        verify(jdbc).queryForList(argThat(sql -> sql.contains("credential.cookie_status=1")
                && sql.contains("state.retry_after")));
        verify(tasks).schedule(any(Runnable.class), eq(clock.instant().plusSeconds(11 * 60L)));
    }
}
