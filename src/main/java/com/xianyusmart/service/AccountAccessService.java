package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 团队成员的闲鱼账号数据范围。 */
@Service
public class AccountAccessService {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_SELECTED = "SELECTED";

    private final JdbcTemplate jdbcTemplate;

    public AccountAccessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Scope loadScope(SysUser user) {
        if (user == null || SCOPE_ALL.equalsIgnoreCase(user.getAccountScopeMode())) {
            return new Scope(true, Set.of());
        }
        List<Long> ids = jdbcTemplate.queryForList(
                """
                SELECT xianyu_account_id FROM sys_user_account_scope WHERE user_id=?
                UNION
                SELECT member.xianyu_account_id FROM sys_user_account_group_scope scope
                  JOIN xianyu_account_group_member member ON member.tenant_id=scope.tenant_id AND member.group_id=scope.group_id
                 WHERE scope.user_id=? ORDER BY xianyu_account_id
                """,
                Long.class, user.getId(), user.getId());
        return new Scope(false, Set.copyOf(ids));
    }

    public boolean canAccess(Long accountId) {
        if (accountId == null) {
            return false;
        }
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        return scope == null || scope.unrestricted() || scope.accountIds().contains(accountId);
    }

    public void requireAccess(Long accountId) {
        if (!canAccess(accountId)) {
            throw new BusinessException(403, "当前成员未获授权访问该闲鱼账号");
        }
    }

    @Transactional
    public void replaceScope(Long userId, Long tenantId, String mode, List<Long> requestedAccountIds) {
        replaceScope(userId, tenantId, mode, requestedAccountIds, List.of());
    }

    @Transactional
    public void replaceScope(Long userId, Long tenantId, String mode, List<Long> requestedAccountIds,
                             List<Long> requestedGroupIds) {
        String normalizedMode = normalizeMode(mode);
        LinkedHashSet<Long> accountIds = new LinkedHashSet<>();
        if (SCOPE_SELECTED.equals(normalizedMode) && requestedAccountIds != null) {
            requestedAccountIds.stream().filter(id -> id != null && id > 0).forEach(accountIds::add);
        }
        for (Long accountId : accountIds) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM xianyu_account WHERE id = ? AND tenant_id = ?", Long.class,
                    accountId, tenantId);
            if (count == null || count == 0) {
                throw new BusinessException(400, "账号 " + accountId + " 不属于当前经营主体");
            }
        }
        LinkedHashSet<Long> groupIds = new LinkedHashSet<>();
        if (SCOPE_SELECTED.equals(normalizedMode) && requestedGroupIds != null) {
            requestedGroupIds.stream().filter(id -> id != null && id > 0).forEach(groupIds::add);
        }
        for (Long groupId : groupIds) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM xianyu_account_group WHERE id=? AND tenant_id=?", Long.class, groupId, tenantId);
            if (count == null || count == 0) throw new BusinessException(400, "店铺分组 " + groupId + " 不属于当前经营主体");
        }
        jdbcTemplate.update("DELETE FROM sys_user_account_scope WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM sys_user_account_group_scope WHERE user_id = ?", userId);
        for (Long accountId : accountIds) {
            jdbcTemplate.update(
                    "INSERT INTO sys_user_account_scope (user_id, xianyu_account_id) VALUES (?, ?)",
                    userId, accountId);
        }
        for (Long groupId : groupIds) {
            jdbcTemplate.update("INSERT INTO sys_user_account_group_scope (tenant_id,user_id,group_id) VALUES (?,?,?)",
                    tenantId,userId,groupId);
        }
    }

    public List<Long> getAccountIds(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT xianyu_account_id FROM sys_user_account_scope WHERE user_id = ? ORDER BY xianyu_account_id",
                Long.class, userId);
    }

    public List<Long> getGroupIds(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT group_id FROM sys_user_account_group_scope WHERE user_id=? ORDER BY group_id",Long.class,userId);
    }

    public static String normalizeMode(String value) {
        return SCOPE_SELECTED.equalsIgnoreCase(value) ? SCOPE_SELECTED : SCOPE_ALL;
    }

    public record Scope(boolean unrestricted, Set<Long> accountIds) {
    }
}
