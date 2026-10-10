package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.event.AccountAuthenticatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 当前登录成员可见账号的一次性上线入口。 */
@Service
public class AccountLoginBootstrapService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher publisher;

    public AccountLoginBootstrapService(JdbcTemplate jdbcTemplate, ApplicationEventPublisher publisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
    }

    public Map<String, Object> start() {
        Long tenantId = TenantContext.get();
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM xianyu_account WHERE tenant_id=? AND status=1 ORDER BY id", Long.class, tenantId);
        if (scope != null && !scope.unrestricted()) {
            ids = ids.stream().filter(scope.accountIds()::contains).toList();
        }
        ids.forEach(id -> publisher.publishEvent(new AccountAuthenticatedEvent(id, "SYSTEM_LOGIN")));
        return Map.of("accepted", true, "accountCount", ids.size(), "accountIds", ids,
                "message", ids.isEmpty() ? "当前范围没有可上线账号" : "已开始上线并刷新可见账号");
    }
}
