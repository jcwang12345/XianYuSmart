package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可认领、可追踪、可关闭的运营异常队列。 */
@Service
public class OperationalIssueService {

    private static final Set<String> STATUSES = Set.of("OPEN", "CLAIMED", "IN_PROGRESS", "RESOLVED", "IGNORED");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");

    private final JdbcTemplate jdbcTemplate;
    private final OperationLogService operationLogService;
    private final NotificationCenterService notificationCenterService;

    public OperationalIssueService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService,
                                   NotificationCenterService notificationCenterService) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
        this.notificationCenterService = notificationCenterService;
    }

    /** 将既有可靠性队列同步为运营任务；唯一键保证刷新不会重复建单。 */
    public int refreshFromSources() {
        Long tenantId = requireTenantId();
        // 账号恢复后自动关闭对应基础设施事件，避免历史异常长期占据驾驶舱。
        jdbcTemplate.update("""
                UPDATE operational_issue issue
                JOIN xianyu_account account ON account.id = issue.xianyu_account_id
                LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id = account.id
                SET issue.status = 'RESOLVED', issue.resolution_note = '系统检测到账号已恢复',
                    issue.resolved_time = NOW(3)
                WHERE issue.tenant_id = ? AND issue.issue_type = 'ACCOUNT_HEALTH'
                  AND issue.status NOT IN ('RESOLVED','IGNORED')
                  AND account.status = 1 AND cookie.cookie_status = 1
                """, tenantId);
        int created = 0;
        created += importRows(tenantId, """
                SELECT account.id, account.id account_id,
                       CONCAT('account-health:', account.id) dedupe_key,
                       '账号连接或凭证异常' title,
                       CONCAT('账号状态=', account.status, '，凭证状态=', COALESCE(cookie.cookie_status, 0)) description,
                       CAST(account.id AS CHAR) source_id,
                       'CRITICAL' severity
                FROM xianyu_account account
                LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id = account.id
                WHERE account.tenant_id = ? AND (account.status <> 1 OR COALESCE(cookie.cookie_status, 0) <> 1)
                """ + directAccountCondition("account"), "ACCOUNT_HEALTH", "ACCOUNT");
        created += importRows(tenantId, """
                SELECT source.id, source.xianyu_account_id account_id,
                       CONCAT('delivery:', source.id, ':', source.exception_revision) dedupe_key,
                       COALESCE(source.goods_title, '自动发货订单') title,
                       COALESCE(source.last_error_message, source.fail_reason, '等待人工核对') description,
                       source.order_id source_id,
                       CASE WHEN source.delivery_status = 'REVIEW_REQUIRED' THEN 'CRITICAL' ELSE 'WARNING' END severity
                FROM xianyu_goods_order source
                WHERE source.tenant_id = ? AND source.delivery_status IN ('FAILED','REVIEW_REQUIRED')
                """ + accountCondition("source"), "DELIVERY", "ORDER");
        created += importRows(tenantId, """
                SELECT source.id, source.xianyu_account_id account_id,
                       CONCAT('reply:', source.id, ':', source.exception_revision) dedupe_key,
                       '自动回复失败' title,
                       COALESCE(source.last_error_message, '回复发送失败') description,
                       COALESCE(source.pnm_id, CAST(source.id AS CHAR)) source_id,
                       'WARNING' severity
                FROM xianyu_goods_auto_reply_record source
                WHERE source.tenant_id = ? AND source.state IN (-1,3)
                """ + accountCondition("source"), "AUTO_REPLY", "MESSAGE");
        created += importRows(tenantId, """
                SELECT source.id, source.xianyu_account_id account_id,
                       CONCAT('supply:', source.id, ':', source.exception_revision) dedupe_key,
                       '外部卡密供货待核对' title,
                       COALESCE(source.error_message, '供货结果不确定') description,
                       COALESCE(source.order_id, CAST(source.id AS CHAR)) source_id,
                       'CRITICAL' severity
                FROM xianyu_kami_external_request source
                WHERE source.tenant_id = ? AND (source.request_status IN ('FAILED','REVIEW_REQUIRED')
                    OR (source.request_status = 'PROCESSING' AND source.update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE)))
                """ + accountCondition("source"), "EXTERNAL_SUPPLY", "SUPPLY_REQUEST");
        created += importRows(tenantId, """
                SELECT source.id, source.xianyu_account_id account_id,
                       CONCAT('merchant-task:', source.id) dedupe_key,
                       CONCAT('运营任务失败：', source.task_type) title,
                       COALESCE(source.error_message, '任务执行失败') description,
                       CAST(source.id AS CHAR) source_id,
                       CASE WHEN source.task_type IN ('PUBLISH','CONFIRM_SHIPMENT') THEN 'CRITICAL' ELSE 'WARNING' END severity
                FROM merchant_task source
                WHERE source.tenant_id = ? AND source.status = -1 AND source.attempt_count >= source.max_attempts
                """ + accountCondition("source"), "MERCHANT_TASK", "MERCHANT_TASK");
        created += importRows(tenantId, """
                SELECT source.id, source.xianyu_account_id account_id,
                       CONCAT('conversation-sla:', source.id) dedupe_key,
                       '客服首次响应超时' title,
                       CONCAT('会话 ', source.session_id, ' 已超过 5 分钟未确认首次回复') description,
                       source.session_id source_id,
                       CASE WHEN source.sla_due_time < DATE_SUB(NOW(3), INTERVAL 15 MINUTE)
                            THEN 'CRITICAL' ELSE 'WARNING' END severity
                FROM conversation_assignment source
                WHERE source.tenant_id = ? AND source.status <> 'CLOSED'
                  AND source.first_response_time IS NULL AND source.sla_due_time < NOW(3)
                """ + accountCondition("source"), "CONVERSATION_SLA", "CONVERSATION");
        return created;
    }

    private int importRows(Long tenantId, String sql, String issueType, String sourceType) {
        int inserted = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, tenantId)) {
            inserted += report(issueType, String.valueOf(row.get("dedupe_key")),
                    longValue(row.get("account_id")), String.valueOf(row.get("severity")),
                    String.valueOf(row.get("title")), String.valueOf(row.get("description")),
                    sourceType, String.valueOf(row.get("source_id")), null);
        }
        return inserted;
    }

    @Transactional
    public int report(String issueType, String dedupeKey, Long accountId, String severity,
                      String title, String description, String sourceType, String sourceId,
                      LocalDateTime dueTime) {
        Long tenantId = resolveTenantId(accountId);
        String normalizedIssueType = trim(issueType, 48);
        String normalizedDedupeKey = trim(dedupeKey, 255);
        String normalizedTitle = trim(title, 255);
        if (normalizedIssueType == null || normalizedIssueType.isBlank()
                || normalizedDedupeKey == null || normalizedDedupeKey.isBlank()
                || normalizedTitle == null || normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("运营异常类型、去重键和标题不能为空");
        }
        String normalizedSeverity = SEVERITIES.contains(severity) ? severity : "WARNING";
        List<String> previous = jdbcTemplate.queryForList(
                "SELECT status FROM operational_issue WHERE tenant_id = ? AND dedupe_key = ?",
                String.class, tenantId, normalizedDedupeKey);
        int changed = jdbcTemplate.update("""
                INSERT INTO operational_issue
                    (tenant_id, issue_type, dedupe_key, severity, status, xianyu_account_id,
                     source_type, source_id, title, description, due_time)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    severity = VALUES(severity), title = VALUES(title), description = VALUES(description),
                    due_time = VALUES(due_time), last_occurred_time = NOW(3),
                    occurrence_count = IF(TIMESTAMPDIFF(SECOND, last_occurred_time, NOW(3)) >= 60,
                                          occurrence_count + 1, occurrence_count),
                    status = IF(status IN ('RESOLVED','IGNORED'), 'OPEN', status),
                    resolved_time = IF(status IN ('RESOLVED','IGNORED'), NULL, resolved_time)
                """, tenantId, normalizedIssueType, normalizedDedupeKey, normalizedSeverity, accountId,
                trim(sourceType, 48), trim(sourceId, 128), normalizedTitle, trim(description, 2000),
                dueTime == null ? null : Timestamp.valueOf(dueTime));
        if (previous.isEmpty() || previous.stream().anyMatch(value -> "RESOLVED".equals(value) || "IGNORED".equals(value))) {
            String eventType = "CONVERSATION_SLA".equals(normalizedIssueType) ? "CONVERSATION_SLA_BREACHED" : "OPERATIONAL_ISSUE_CREATED";
            notificationCenterService.dispatch(eventType, accountId, normalizedTitle, trim(description, 2000),
                    Map.of("issueType", normalizedIssueType, "severity", normalizedSeverity, "dedupeKey", normalizedDedupeKey));
        }
        return changed;
    }

    public List<Map<String, Object>> list(String status, String severity, Long accountId, Integer requestedLimit) {
        refreshFromSources();
        Long tenantId = requireTenantId();
        int limit = Math.max(1, Math.min(requestedLimit == null ? 200 : requestedLimit, 500));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT issue.id, issue.issue_type issueType, issue.severity, issue.status,
                       issue.xianyu_account_id accountId, account.account_note accountNote,
                       issue.source_type sourceType, issue.source_id sourceId,
                       issue.title, issue.description, issue.resolution_note resolutionNote,
                       issue.assigned_user_id assignedUserId, issue.assigned_username assignedUsername,
                       issue.due_time dueTime, issue.occurrence_count occurrenceCount,
                       issue.first_occurred_time firstOccurredTime, issue.last_occurred_time lastOccurredTime,
                       issue.resolved_time resolvedTime, issue.updated_time updatedTime
                FROM operational_issue issue
                LEFT JOIN xianyu_account account ON account.id = issue.xianyu_account_id
                WHERE issue.tenant_id = ?
                """);
        args.add(tenantId);
        sql.append(accountCondition("issue"));
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!STATUSES.contains(normalized)) throw new BusinessException(400, "异常状态无效");
            sql.append(" AND issue.status = ?");
            args.add(normalized);
        } else {
            sql.append(" AND issue.status NOT IN ('RESOLVED','IGNORED')");
        }
        if (severity != null && !severity.isBlank()) {
            String normalized = severity.trim().toUpperCase();
            if (!SEVERITIES.contains(normalized)) throw new BusinessException(400, "严重度无效");
            sql.append(" AND issue.severity = ?");
            args.add(normalized);
        }
        if (accountId != null) {
            sql.append(" AND issue.xianyu_account_id = ?");
            args.add(accountId);
        }
        sql.append(" ORDER BY FIELD(issue.severity,'CRITICAL','WARNING','INFO'), issue.last_occurred_time DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public void transition(Long issueId, String targetStatus, String note) {
        String normalized = targetStatus == null ? "" : targetStatus.trim().toUpperCase();
        if (!STATUSES.contains(normalized) || "OPEN".equals(normalized)) {
            throw new BusinessException(400, "目标状态无效");
        }
        Long tenantId = requireTenantId();
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        int updated;
        if ("CLAIMED".equals(normalized) || "IN_PROGRESS".equals(normalized)) {
            updated = jdbcTemplate.update("""
                    UPDATE operational_issue
                    SET status = ?, assigned_user_id = ?, assigned_username = ?, resolution_note = NULL
                    WHERE id = ? AND tenant_id = ? AND status NOT IN ('RESOLVED','IGNORED')
                    """ + accountCondition("operational_issue"), normalized, userId, username, issueId, tenantId);
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE operational_issue
                    SET status = ?, resolution_note = ?, resolved_time = NOW(3),
                        assigned_user_id = COALESCE(assigned_user_id, ?),
                        assigned_username = COALESCE(assigned_username, ?)
                    WHERE id = ? AND tenant_id = ? AND status NOT IN ('RESOLVED','IGNORED')
                    """ + accountCondition("operational_issue"), normalized, trim(note, 1000), userId, username, issueId, tenantId);
        }
        if (updated == 0) throw new BusinessException(409, "异常已被其他成员处理或不存在");
        operationLogService.log(null, "ISSUE_TRANSITION", "OPERATIONS",
                "运营异常 #" + issueId + " 更新为 " + normalized, 1,
                "OPERATIONAL_ISSUE", String.valueOf(issueId), null, null, null, null);
    }

    private String accountCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1 = 0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private String directAccountCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1 = 0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".id IN (" + ids + ")";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "登录状态已失效");
        return tenantId;
    }

    private Long resolveTenantId(Long accountId) {
        Long tenantId = TenantContext.get();
        if (tenantId != null) return tenantId;
        if (accountId != null) {
            List<Long> tenantIds = jdbcTemplate.queryForList(
                    "SELECT tenant_id FROM xianyu_account WHERE id = ?", Long.class, accountId);
            if (!tenantIds.isEmpty()) return tenantIds.get(0);
        }
        throw new BusinessException(401, "无法确定运营异常所属租户");
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String trim(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
