package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向 20～30 个账号的异常优先运营总览。 */
@Service
public class AccountCommandCenterService {

    private final JdbcTemplate jdbcTemplate;
    private final WebSocketService webSocketService;
    private final RiskControlService riskControlService;
    private final OperationalIssueService issueService;

    public AccountCommandCenterService(JdbcTemplate jdbcTemplate, WebSocketService webSocketService,
                                       RiskControlService riskControlService, OperationalIssueService issueService) {
        this.jdbcTemplate = jdbcTemplate;
        this.webSocketService = webSocketService;
        this.riskControlService = riskControlService;
        this.issueService = issueService;
    }

    public List<Map<String, Object>> accounts() {
        issueService.refreshFromSources();
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "登录状态已失效");
        String scope = accountCondition("account");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT account.id accountId, account.account_note accountNote, account.unb,
                       account.status accountStatus, cookie.cookie_status cookieStatus,
                       cookie.expire_time credentialExpireTime,
                       (SELECT COUNT(*) FROM xianyu_goods_order orders
                         WHERE orders.tenant_id = account.tenant_id AND orders.xianyu_account_id = account.id
                           AND orders.delivery_status IN ('PENDING','PROCESSING','RETRY_WAIT')) pendingDeliveryCount,
                       (SELECT COUNT(*) FROM xianyu_goods_order orders
                         WHERE orders.tenant_id = account.tenant_id AND orders.xianyu_account_id = account.id
                           AND orders.delivery_status IN ('FAILED','REVIEW_REQUIRED')) failedDeliveryCount,
                       (SELECT COUNT(*) FROM xianyu_goods_auto_reply_record replies
                         WHERE replies.tenant_id = account.tenant_id AND replies.xianyu_account_id = account.id
                           AND replies.state IN (-1,3)) failedReplyCount,
                       (SELECT COUNT(*) FROM xianyu_goods_order orders
                         WHERE orders.tenant_id = account.tenant_id AND orders.xianyu_account_id = account.id
                           AND orders.create_time >= CURRENT_DATE()) todayOrderCount,
                       (SELECT COUNT(*) FROM xianyu_chat_message messages
                         WHERE messages.tenant_id = account.tenant_id AND messages.xianyu_account_id = account.id
                           AND messages.create_time >= DATE_SUB(NOW(3), INTERVAL 24 HOUR)) message24hCount,
                       (SELECT COUNT(*) FROM operational_issue issue
                         WHERE issue.tenant_id = account.tenant_id AND issue.xianyu_account_id = account.id
                           AND issue.status NOT IN ('RESOLVED','IGNORED')) openIssueCount,
                       (SELECT MAX(messages.create_time) FROM xianyu_chat_message messages
                         WHERE messages.tenant_id = account.tenant_id AND messages.xianyu_account_id = account.id) lastMessageTime,
                       (SELECT MAX(orders.create_time) FROM xianyu_goods_order orders
                         WHERE orders.tenant_id = account.tenant_id AND orders.xianyu_account_id = account.id) lastOrderTime
                FROM xianyu_account account
                LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id = account.id
                WHERE account.tenant_id = ?
                """ + scope + " ORDER BY openIssueCount DESC, failedDeliveryCount DESC, account.id", tenantId);
        return rows.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            Long accountId = ((Number) row.get("accountId")).longValue();
            boolean connected = webSocketService.isConnected(accountId);
            RiskControlService.GuardStatus guard = riskControlService.getStatus(accountId);
            result.put("websocketConnected", connected);
            result.put("riskState", guard.state().name());
            result.put("riskReason", guard.reason());
            result.put("attentionLevel", attentionLevel(row, connected, guard));
            return result;
        }).toList();
    }

    private String attentionLevel(Map<String, Object> row, boolean connected, RiskControlService.GuardStatus guard) {
        long failedDelivery = number(row.get("failedDeliveryCount"));
        long openIssues = number(row.get("openIssueCount"));
        if (failedDelivery > 0 || guard.state() == RiskControlService.GuardState.CIRCUIT_OPEN) return "CRITICAL";
        if (!connected || number(row.get("failedReplyCount")) > 0 || openIssues > 0) return "WARNING";
        return "HEALTHY";
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String accountCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1 = 0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".id IN (" + ids + ")";
    }
}
