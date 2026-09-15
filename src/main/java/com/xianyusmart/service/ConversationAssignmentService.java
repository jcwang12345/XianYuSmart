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
        int inserted = jdbcTemplate.update("""
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
                HAVING MIN(CASE WHEN messages.sender_user_id <> account.unb THEN messages.create_time END) IS NOT NULL
                ON DUPLICATE KEY UPDATE buyer_user_id = VALUES(buyer_user_id),
                    first_message_time = LEAST(first_message_time, VALUES(first_message_time)),
                    first_response_time = COALESCE(first_response_time, VALUES(first_response_time)),
                    last_message_time = GREATEST(last_message_time, VALUES(last_message_time)),
                    sla_due_time = COALESCE(sla_due_time, VALUES(sla_due_time))
                """, tenantId);
        projectBuyersFromConversations(tenantId);
        jdbcTemplate.update("""
                UPDATE conversation_assignment assignment
                JOIN xianyu_account account ON account.id=assignment.xianyu_account_id
                   SET assignment.unread_count=(
                         SELECT COUNT(*) FROM xianyu_chat_message message
                          WHERE message.tenant_id=assignment.tenant_id
                            AND message.xianyu_account_id=assignment.xianyu_account_id
                            AND message.s_id=assignment.session_id
                            AND message.sender_user_id<>account.unb
                            AND message.message_time>COALESCE(assignment.last_read_message_time,0)
                       ),
                       assignment.manual_takeover_state=CASE WHEN EXISTS (
                         SELECT 1 FROM xianyu_human_intervention_record intervention
                          WHERE intervention.tenant_id=assignment.tenant_id
                            AND intervention.xianyu_account_id=assignment.xianyu_account_id
                            AND intervention.s_id=assignment.session_id AND intervention.end_time>NOW(3)
                       ) THEN 'MANUAL' ELSE 'AUTO' END,
                       assignment.manual_takeover_until=(
                         SELECT MAX(intervention.end_time) FROM xianyu_human_intervention_record intervention
                          WHERE intervention.tenant_id=assignment.tenant_id
                            AND intervention.xianyu_account_id=assignment.xianyu_account_id
                            AND intervention.s_id=assignment.session_id AND intervention.end_time>NOW(3)
                       )
                 WHERE assignment.tenant_id=?
                """ + accountCondition("assignment"), tenantId);
        return inserted;
    }

    private void projectBuyersFromConversations(Long tenantId) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_buyer_profile
                    (tenant_id, xianyu_account_id, buyer_user_id, buyer_user_name, last_interaction_time)
                SELECT assignment.tenant_id, assignment.xianyu_account_id, assignment.buyer_user_id,
                       COALESCE(
                         (SELECT message.sender_user_name FROM xianyu_chat_message message
                           WHERE message.tenant_id=assignment.tenant_id
                             AND message.xianyu_account_id=assignment.xianyu_account_id
                             AND message.s_id=assignment.session_id
                             AND message.sender_user_id=assignment.buyer_user_id
                           ORDER BY message.message_time DESC,message.id DESC LIMIT 1),
                         (SELECT orders.buyer_user_name FROM xianyu_goods_order orders
                           WHERE orders.tenant_id=assignment.tenant_id
                             AND orders.xianyu_account_id=assignment.xianyu_account_id
                             AND orders.buyer_user_id=assignment.buyer_user_id
                           ORDER BY orders.create_time DESC,orders.id DESC LIMIT 1)
                       ), assignment.last_message_time
                  FROM conversation_assignment assignment
                 WHERE assignment.tenant_id=?
                   AND assignment.buyer_user_id IS NOT NULL AND assignment.buyer_user_id<>''
                """ + accountCondition("assignment") + """
                ON DUPLICATE KEY UPDATE
                    buyer_user_name=COALESCE(NULLIF(VALUES(buyer_user_name),''),buyer_user_name),
                    last_interaction_time=GREATEST(
                        COALESCE(last_interaction_time,VALUES(last_interaction_time)),
                        VALUES(last_interaction_time))
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
                       assignment.buyer_user_id buyerUserId, assignment.unread_count unreadCount,
                       assignment.last_read_at lastReadAt, assignment.last_read_by lastReadBy,
                       assignment.manual_takeover_state manualTakeoverState,
                       assignment.manual_takeover_until manualTakeoverUntil,
                       assignment.auto_reply_state autoReplyState,
                       assignment.handoff_status handoffStatus,
                       assignment.handoff_reason_code handoffReasonCode,
                       assignment.handoff_task_id handoffTaskId,
                       assignment.handoff_created_time handoffCreatedTime,
                       assignment.history_sync_status historySyncStatus,
                       assignment.history_coverage_status historyCoverageStatus,
                       assignment.history_last_synced_time historyLastSyncedTime,
                       assignment.history_last_error historyLastError,
                       assignment.status, assignment.priority,
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

    @Transactional
    public void markRead(Long accountId, String sessionId) {
        int updated = jdbcTemplate.update("""
                UPDATE conversation_assignment assignment
                   SET last_read_message_time=COALESCE((SELECT MAX(message.message_time)
                         FROM xianyu_chat_message message
                        WHERE message.tenant_id=assignment.tenant_id
                          AND message.xianyu_account_id=assignment.xianyu_account_id
                          AND message.s_id=assignment.session_id), last_read_message_time),
                       last_read_at=NOW(3), last_read_by=?, unread_count=0
                 WHERE assignment.tenant_id=? AND assignment.xianyu_account_id=? AND assignment.session_id=?
                """ + accountCondition("assignment"), UserContext.getUserId(), requireTenantId(), accountId, sessionId);
        if (updated == 0) throw new BusinessException(404, "会话不存在或不在当前店铺权限范围");
    }

    public void markHistorySync(Long accountId, String sessionId, int received, int requested, String error) {
        String status = error == null ? "SUCCEEDED" : "FAILED";
        String coverage = error != null ? "PARTIAL" : received < requested ? "FULL" : "PARTIAL";
        jdbcTemplate.update("""
                UPDATE conversation_assignment
                   SET history_sync_status=?, history_coverage_status=?,
                       history_last_synced_time=CASE WHEN ? IS NULL THEN NOW(3) ELSE history_last_synced_time END,
                       history_last_error=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, status, coverage, error, trim(error), requireTenantId(), accountId, sessionId);
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
