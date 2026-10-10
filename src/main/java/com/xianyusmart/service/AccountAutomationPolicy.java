package com.xianyusmart.service;

import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Local automation state is independent of platform credentials and verification state. */
@Service
public class AccountAutomationPolicy {
    public enum Action { CONNECT, REFRESH, REPLY, DELIVERY, DISCOVERY, SYNC }
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.locks.ReentrantReadWriteLock[] gates =
            java.util.stream.IntStream.range(0,128).mapToObj(i -> new java.util.concurrent.locks.ReentrantReadWriteLock(true))
                    .toArray(java.util.concurrent.locks.ReentrantReadWriteLock[]::new);

    public AccountAutomationPolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean allows(Long accountId, Action action) {
        if (accountId == null || action == null) return false;
        var rows = jdbc.queryForList("""
                SELECT COALESCE(profile.lifecycle_state,'ACTIVE') lifecycleState,
                       COALESCE(profile.auto_reply_allowed,1) autoReplyAllowed,
                       COALESCE(profile.auto_delivery_allowed,1) autoDeliveryAllowed
                  FROM xianyu_account account
                  LEFT JOIN xianyu_account_local_profile profile
                    ON profile.tenant_id=account.tenant_id AND profile.xianyu_account_id=account.id
                 WHERE account.id=?
                """, accountId);
        if (rows.size() != 1) return false;
        var state = rows.getFirst();
        if (!"ACTIVE".equals(state.get("lifecycleState"))) return false;
        return switch (action) {
            case REPLY -> enabled(state.get("autoReplyAllowed"));
            case DELIVERY, DISCOVERY -> enabled(state.get("autoDeliveryAllowed"));
            default -> true;
        };
    }

    public void requireAllowed(Long accountId, Action action) {
        if (!allows(accountId, action)) throw new BusinessException(409, "账号本地状态或策略已暂停此操作，请先检查账号设置");
    }

    /** Hold the execution guard across the actual platform operation, including its outcome write. */
    public <T> T guarded(Long accountId, Action action, java.util.function.Supplier<T> operation) {
        var lock=gate(accountId).readLock();
        lock.lock();
        try { requireAllowed(accountId,action); return operation.get(); }
        finally {
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
                releaseAfterCompletion(lock);
            else lock.unlock();
        }
    }

    /** Release only after commit/rollback so a new worker cannot read the old state mid-command. */
    public void lockMutationUntilCompletion(Long accountId) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Account mutation requires a transaction");
        var lock=gate(accountId).writeLock();
        try {
            if(!lock.tryLock(2,java.util.concurrent.TimeUnit.SECONDS))
                throw new BusinessException(409,"账号正在执行外发操作，请稍后重试停用或归档");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new BusinessException(409,"账号状态操作已中断，请稍后重试");
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { lock.unlock(); }
                });
    }

    public void protectTenantExecutionClaims() {
        Long tenant=com.xianyusmart.context.TenantContext.get();
        if(tenant==null || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Tenant execution claims require a transaction");
        // Acquire execution guards before the existing order row locks, preventing lock-order
        // inversion against a lifecycle command. Claims retain the guards through commit.
        for(Long accountId:jdbc.queryForList("SELECT id FROM xianyu_account WHERE tenant_id=? ORDER BY id",Long.class,tenant)) {
            var lock=gate(accountId).readLock();lock.lock();releaseAfterCompletion(lock);
        }
    }

    private void releaseAfterCompletion(java.util.concurrent.locks.Lock lock) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { lock.unlock(); }
                });
    }

    private java.util.concurrent.locks.ReentrantReadWriteLock gate(Long accountId) {
        if(accountId==null)throw new BusinessException(400,"账号ID不能为空");
        return gates[Math.floorMod(accountId.hashCode(),gates.length)];
    }

    private boolean enabled(Object value) {
        return value instanceof Number number && number.intValue() == 1;
    }
}
