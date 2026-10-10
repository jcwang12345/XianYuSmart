package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 每日两次的画像刷新，只选择正常账号并尊重成功间隔与失败恢复时间。 */
@Slf4j
@Component
public class ShopProfileSyncScheduler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final ShopProfileSyncService syncService;
    private final TaskScheduler taskScheduler;
    private final BackgroundPlatformActivityPolicy backgroundPlatformActivityPolicy;
    private final Clock clock;

    @Value("${xianyu.shop-profile.schedule-enabled:true}")
    private boolean enabled = true;

    @org.springframework.beans.factory.annotation.Autowired
    public ShopProfileSyncScheduler(JdbcTemplate jdbcTemplate,
                                    ShopProfileSyncService syncService,
                                    @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                    BackgroundPlatformActivityPolicy backgroundPlatformActivityPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.syncService = syncService;
        this.taskScheduler = taskScheduler;
        this.backgroundPlatformActivityPolicy = backgroundPlatformActivityPolicy;
        this.clock = Clock.systemUTC();
    }

    ShopProfileSyncScheduler(JdbcTemplate jdbcTemplate, ShopProfileSyncService syncService,
                             TaskScheduler taskScheduler, BackgroundPlatformActivityPolicy policy, Clock clock) {
        this.jdbcTemplate = jdbcTemplate; this.syncService = syncService; this.taskScheduler = taskScheduler;
        this.backgroundPlatformActivityPolicy = policy; this.clock = clock;
    }

    @Scheduled(cron = "${xianyu.shop-profile.schedule-cron:0 30 10,17 * * *}", zone = "Asia/Shanghai")
    public void scheduleEligibleAccounts() {
        if (!backgroundPlatformActivityPolicy.enabled() || !enabled) return;
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList("""
                SELECT account.tenant_id tenantId,account.id accountId
                  FROM xianyu_account account
                  LEFT JOIN xianyu_account_dataset_state state
                    ON state.tenant_id=account.tenant_id AND state.xianyu_account_id=account.id
                   AND state.dataset_code='SHOP_PROFILE'
                 WHERE account.status=1
                   AND EXISTS (SELECT 1 FROM xianyu_cookie credential
                                WHERE credential.tenant_id=account.tenant_id
                                  AND credential.xianyu_account_id=account.id
                                  AND credential.cookie_status=1 AND credential.cookie_text IS NOT NULL)
                   AND (state.last_success_time IS NULL OR state.last_success_time<DATE_SUB(NOW(3),INTERVAL 2 HOUR))
                   AND (state.retry_after IS NULL OR state.retry_after<=NOW(3))
                 ORDER BY account.tenant_id,account.id LIMIT 500
                """);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        String window = now.getHour() < 14 ? "AM" : "PM";
        LocalDate day = now.toLocalDate();
        for (Map<String, Object> row : accounts) {
            long tenantId = ((Number) row.get("tenantId")).longValue();
            long accountId = ((Number) row.get("accountId")).longValue();
            int jitterMinutes = Math.floorMod(Long.hashCode(tenantId * 31 + accountId), 21);
            Instant startAt = clock.instant().plusSeconds(jitterMinutes * 60L);
            String requestId = "profile-schedule-" + day + "-" + window + "-" + accountId;
            taskScheduler.schedule(() -> run(tenantId, accountId, requestId), startAt);
        }
    }

    private void run(Long tenantId, Long accountId, String requestId) {
        try {
            TenantContext.set(tenantId);
            syncService.runBlocking(accountId, requestId, "SCHEDULED");
        } catch (Exception e) {
            log.warn("计划店铺画像同步失败: tenantId={}, accountId={}, errorType={}",
                    tenantId, accountId, e.getClass().getSimpleName());
        } finally {
            TenantContext.clear();
        }
    }
}
