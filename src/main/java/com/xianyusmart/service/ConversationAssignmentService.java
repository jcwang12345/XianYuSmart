package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 跨店客服会话认领、优先级和首次响应 SLA。 */
@Service
public class ConversationAssignmentService {

    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "CLOSED");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private final JdbcTemplate jdbcTemplate;

    public ConversationAssignmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int refresh() {
        Long tenantId = requireTenantId();
        return jdbcTemplate.update("""
                INSERT INTO conversation_assignment
                    (tenant_id, xianyu_account_id, session_id, buyer_user_id,
                     first_message_time, first_response_time, last_message_time, sla_due_time)
                SELECT messages.tenant_id, messages.xianyu_account_id, messages.s_id,
                       MAX(CASE WHEN messages.sender_user_id <> account.unb THEN messages.sender_user_id END),
                       MIN(CASE WHEN messages.sender_user_id <> account.unb THEN messages.create_time END),
                       MIN(CASE WHEN messages.sender_user_id = account.unb THEN messages.create_time END),
                       MAX(messages.create_time),
                       DATE_ADD(MIN(CASE WHEN messages.sender_user_id <> account.unb THEN messages.create_time END), INTERVAL 5 MINUTE)
                FROM xianyu_chat_message messages
                JOIN xianyu_account account ON account.id = messages.xianyu_account_id
                WHERE messages.tenant_id = ? AND messages.s_id IS NOT NULL
                  AND messages.create_time >= DATE_SUB(NOW(3), INTERVAL 7 DAY)
                """ + accountCondition("messages") + """
                GROUP BY messages.tenant_id, messages.xianyu_account_id, messages.s_id
                HAVING first_message_time IS NOT NULL
                ON DUPLICATE KEY UPDATE buyer_user_id = VALUES(buyer_user_id),
                    first_message_time = LEAST(first_message_time, VALUES(first_message_time)),
                    first_response_time = COALESCE(first_response_time, VALUES(first_response_time)),
                    last_message_time = GREATEST(last_message_time, VALUES(last_message_time)),
                    sla_due_time = COALESCE(sla_due_time, VALUES(sla_due_time))
                """, tenantId);
    }

    public List<Map<String, Object>> list(String status, Integer limit) {
        refresh();
        String normalized = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) throw new BusinessException(400, "会话状态无效");
        int max = Math.max(1, Math.min(limit == null ? 200 : limit, 500));
        return jdbcTemplate.queryForList("""
                SELECT assignment.id, assignment.xianyu_account_id accountId,
                       account.account_note accountNote, assignment.session_id sessionId,
                       assignment.buyer_user_id buyerUserId, assignment.status, assignment.priority,
                       assignment.assigned_user_id assignedUserId, assignment.assigned_username assignedUsername,
                       assignment.first_message_time firstMessageTime,
                       assignment.first_response_time firstResponseTime,
                       assignment.last_message_time lastMessageTime, assignment.sla_due_time slaDueTime,
                       assignment.note,
                       CASE WHEN assignment.first_response_time IS NULL AND assignment.sla_due_time < NOW(3)
                            THEN 1 ELSE 0 END slaBreached
                FROM conversation_assignment assignment
                JOIN xianyu_account account ON account.id = assignment.xianyu_account_id
                WHERE assignment.tenant_id = ? AND assignment.status = ?
                """ + accountCondition("assignment") + """
                ORDER BY slaBreached DESC, FIELD(assignment.priority,'URGENT','HIGH','NORMAL','LOW'),
                         assignment.last_message_time DESC LIMIT ?
                """, requireTenantId(), normalized, max);
    }

    @Transactional
    public void update(Long id, String status, String priority, boolean claim, String note) {
        String normalizedStatus = status == null ? null : status.trim().toUpperCase();
        String normalizedPriority = priority == null ? null : priority.trim().toUpperCase();
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) throw new BusinessException(400, "会话状态无效");
        if (normalizedPriority != null && !PRIORITIES.contains(normalizedPriority)) throw new BusinessException(400, "优先级无效");
        int updated = jdbcTemplate.update("""
                UPDATE conversation_assignment SET
                    status = COALESCE(?, status), priority = COALESCE(?, priority),
                    assigned_user_id = CASE WHEN ? THEN ? ELSE assigned_user_id END,
                    assigned_username = CASE WHEN ? THEN ? ELSE assigned_username END,
                    note = COALESCE(?, note)
                WHERE id = ? AND tenant_id = ?
                """ + accountCondition("conversation_assignment"), normalizedStatus, normalizedPriority, claim, UserContext.getUserId(),
                claim, UserContext.getUsername(), trim(note), id, requireTenantId());
        if (updated == 0) throw new BusinessException(404, "会话任务不存在");
    }

    private String accountCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1 = 0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "登录状态已失效");
        return tenantId;
    }

    private String trim(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
