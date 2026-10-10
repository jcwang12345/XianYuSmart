package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/** 手工、批量和计划任务共用的店铺画像同步协调器。 */
@Slf4j
@Service
public class ShopProfileSyncService {

    private final AccountAccessService accountAccessService;
    private final XianyuAccountMapper accountMapper;
    private final AccountService accountService;
    private final ShopProfileCollector collector;
    private final ShopProfileParser parser;
    private final ShopProfilePersistenceService persistence;
    private final JdbcTemplate jdbcTemplate;
    private final Executor executor;
    private final Semaphore globalSlots = new Semaphore(2, true);
    private final ConcurrentMap<Long, ReentrantLock> startLocks = new ConcurrentHashMap<>();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ShopProfileSyncService(AccountAccessService accountAccessService,
                                  XianyuAccountMapper accountMapper,
                                  AccountService accountService,
                                  ShopProfileCollector collector,
                                  ShopProfileParser parser,
                                  ShopProfilePersistenceService persistence,
                                  JdbcTemplate jdbcTemplate,
                                  @Qualifier("taskExecutor") Executor executor) {
        this.accountAccessService = accountAccessService;
        this.accountMapper = accountMapper;
        this.accountService = accountService;
        this.collector = collector;
        this.parser = parser;
        this.persistence = persistence;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.clock = Clock.systemUTC();
    }

    ShopProfileSyncService(AccountAccessService accountAccessService, XianyuAccountMapper accountMapper,
                           AccountService accountService, ShopProfileCollector collector, ShopProfileParser parser,
                           ShopProfilePersistenceService persistence, JdbcTemplate jdbcTemplate,
                           Executor executor, Clock clock) {
        this.accountAccessService = accountAccessService; this.accountMapper = accountMapper;
        this.accountService = accountService; this.collector = collector; this.parser = parser;
        this.persistence = persistence; this.jdbcTemplate = jdbcTemplate; this.executor = executor; this.clock = clock;
    }

    @PostConstruct
    public void reconcileInterruptedAttempts() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofMinutes(10)));
        jdbcTemplate.update("""
                UPDATE xianyu_shop_profile_sync_attempt
                   SET status='FAILED',error_code='TIMEOUT',error_message='服务重启后任务超过执行时限',
                       recovery_hint='可安全重新发起同步',completed_at=NOW(3),retry_after=NOW(3)
                 WHERE status IN ('QUEUED','RUNNING') AND started_at<?
                """, cutoff);
        jdbcTemplate.update("""
                UPDATE xianyu_account_dataset_state state
                JOIN xianyu_shop_profile_sync_attempt attempt
                  ON attempt.tenant_id=state.tenant_id AND attempt.xianyu_account_id=state.xianyu_account_id
                 AND attempt.request_id=state.request_id
                   SET state.sync_status='FAILED',state.last_error_code='TIMEOUT',
                       state.last_error_message='服务重启后任务超过执行时限',state.retry_after=NOW(3),
                       state.recovery_hint='可安全重新发起同步'
                 WHERE state.dataset_code='SHOP_PROFILE' AND attempt.error_code='TIMEOUT'
                """);
    }

    public Map<String, Object> start(Long accountId, String requestId) {
        return start(accountId, requestId, "MANUAL", true);
    }

    public Map<String, Object> runBlocking(Long accountId, String requestId, String triggerType) {
        Map<String, Object> accepted = start(accountId, requestId, triggerType, false);
        if (Boolean.TRUE.equals(accepted.get("idempotentReplay")) || "BUSY".equals(accepted.get("status"))) {
            return accepted;
        }
        execute(accountId, requestId);
        return status(accountId, requestId);
    }

    private Map<String, Object> start(Long accountId, String requestId, String triggerType, boolean async) {
        accountAccessService.requireAccess(accountId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 80) {
            throw new BusinessException(400, "requestId不能为空且不能超过80个字符");
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            throw new BusinessException(404, "账号不存在或缺少平台身份");
        }
        ReentrantLock lock = startLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Object> replay = findAttempt(accountId, requestId);
            if (!replay.isEmpty()) {
                replay.put("idempotentReplay", true);
                if ("QUEUED".equals(replay.get("status"))) replay.put("status", "RUNNING");
                return replay;
            }
            if (hasRunningAttempt(accountId)) {
                return Map.of("accountId", accountId, "requestId", requestId, "status", "BUSY",
                        "message", "该账号已有画像同步任务", "idempotentReplay", false);
            }
            try {
                persistence.createAttempt(accountId, requestId, triggerType);
            } catch (DataIntegrityViolationException e) {
                Map<String, Object> raced = findAttempt(accountId, requestId);
                if (!raced.isEmpty()) {
                    raced.put("idempotentReplay", true);
                    if ("QUEUED".equals(raced.get("status"))) raced.put("status", "RUNNING");
                    return raced;
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
        if (async) executor.execute(() -> execute(accountId, requestId));
        return Map.of("accountId", accountId, "requestId", requestId, "status", "RUNNING",
                "idempotentReplay", false);
    }

    void execute(Long accountId, String requestId) {
        boolean acquired = false;
        try {
            globalSlots.acquire();
            acquired = true;
            persistence.markRunning(accountId, requestId);
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                persistence.completeFailure(accountId, requestId, "NONE", "UNAVAILABLE",
                        "账号不存在或缺少平台身份", "检查账号归属与平台用户ID后重试");
                return;
            }
            if (!credentialReady(accountId)) {
                persistence.completeFailure(accountId, requestId, "NONE", "CREDENTIAL_EXPIRED",
                        "账号凭据已过期，未发起平台读取",
                        "前往连接管理完成扫码或账号验证；上次成功画像仍会保留并标记为历史快照");
                return;
            }
            String cookie = accountService.getCookieByAccountId(accountId);
            ShopProfileCollector.CollectionResult collected = collector.collect(accountId, account.getUnb(), cookie);
            if (!collected.success()) {
                String code = stableCode(collected.errorCode());
                persistence.completeFailure(accountId, requestId, collected.source(), code,
                        collected.errorMessage(), recoveryHint(code));
                return;
            }
            ShopProfileParser.ParsedProfile profile;
            try {
                profile = parser.parseMerged(account.getUnb(), collected);
            } catch (ShopProfileParser.ProfileParseException e) {
                String code = stableCode(e.code());
                persistence.completeFailure(accountId, requestId, collected.source(), code, e.getMessage(),
                        recoveryHint(code));
                return;
            }
            persistence.completeSuccess(accountId, requestId, collected.source(), profile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeFailure(accountId, requestId, "TIMEOUT", "画像同步被中断");
        } catch (Exception e) {
            log.error("店铺画像同步失败: accountId={}, requestId={}, errorType={}",
                    accountId, requestId, e.getClass().getSimpleName());
            safeFailure(accountId, requestId, "UNAVAILABLE", "店铺画像同步失败");
        } finally {
            if (acquired) globalSlots.release();
        }
    }

    public Map<String, Object> latestStatus(Long accountId) {
        accountAccessService.requireAccess(accountId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_id requestId,trigger_type triggerType,status,source,coverage_status coverageStatus,
                       started_at startedAt,completed_at completedAt,retry_after retryAfter,
                       error_code errorCode,error_message errorMessage,recovery_hint recoveryHint
                  FROM xianyu_shop_profile_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? ORDER BY id DESC LIMIT 1
                """, tenant(), accountId);
        return rows.isEmpty() ? unsynced(accountId) : normalizeStatus(accountId, rows.get(0));
    }

    public Map<String, Object> status(Long accountId, String requestId) {
        accountAccessService.requireAccess(accountId);
        Map<String, Object> result = findAttempt(accountId, requestId);
        if (result.isEmpty()) throw new BusinessException(404, "画像同步任务不存在");
        return result;
    }

    private Map<String, Object> findAttempt(Long accountId, String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_id requestId,trigger_type triggerType,status,source,coverage_status coverageStatus,
                       started_at startedAt,completed_at completedAt,retry_after retryAfter,
                       error_code errorCode,error_message errorMessage,recovery_hint recoveryHint
                  FROM xianyu_shop_profile_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND request_id=? LIMIT 1
                """, tenant(), accountId, requestId);
        return rows.isEmpty() ? new LinkedHashMap<>() : normalizeStatus(accountId, rows.get(0));
    }

    private boolean hasRunningAttempt(Long accountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_shop_profile_sync_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND status IN ('QUEUED','RUNNING')
                """, Integer.class, tenant(), accountId);
        return count != null && count > 0;
    }

    private boolean credentialReady(Long accountId) {
        Integer status = jdbcTemplate.queryForObject("""
                SELECT COALESCE((SELECT cookie_status FROM xianyu_cookie
                                  WHERE tenant_id=? AND xianyu_account_id=?
                                  ORDER BY id DESC LIMIT 1),0)
                """, Integer.class, tenant(), accountId);
        return status != null && status == 1;
    }

    private Map<String, Object> normalizeStatus(Long accountId, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("accountId", accountId);
        result.replaceAll((key, value) -> value instanceof Timestamp timestamp ? timestamp.toInstant() : value);
        return result;
    }

    private Map<String, Object> unsynced(Long accountId) {
        return Map.of("accountId", accountId, "status", "UNSYNCED", "source", "NONE",
                "coverageStatus", "UNSYNCED");
    }

    private void safeFailure(Long accountId, String requestId, String code, String message) {
        try {
            persistence.completeFailure(accountId, requestId, "NONE", code, message, "稍后重试");
        } catch (Exception persistenceError) {
            log.error("画像同步失败状态写入失败: accountId={}, requestId={}, errorType={}",
                    accountId, requestId, persistenceError.getClass().getSimpleName());
        }
    }

    private String stableCode(String code) {
        if (code == null) return "UNAVAILABLE";
        return switch (code) {
            case "BUSY", "TIMEOUT", "VERIFICATION_REQUIRED", "CREDENTIAL_EXPIRED", "RATE_LIMITED",
                    "IDENTITY_MISMATCH", "UNAVAILABLE" -> code;
            case "CREDENTIAL_UNAVAILABLE" -> "CREDENTIAL_EXPIRED";
            default -> "UNAVAILABLE";
        };
    }

    private String recoveryHint(String code) {
        return switch (code) {
            case "CREDENTIAL_EXPIRED", "VERIFICATION_REQUIRED" -> "前往连接管理完成扫码或账号验证；12小时后计划任务才会再尝试";
            case "RATE_LIMITED" -> "平台或页面补采已限速；2小时后再试，页面补采每天最多一次";
            case "IDENTITY_MISMATCH" -> "核对账号平台身份；系统不会自动改写账号标识";
            case "TIMEOUT" -> "任务已收敛为超时，可安全重新发起";
            default -> "平台暂不可用；30分钟后重试，上次成功画像仍保留";
        };
    }

    private Long tenant() {
        Long value = TenantContext.get();
        if (value == null || value <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return value;
    }
}
