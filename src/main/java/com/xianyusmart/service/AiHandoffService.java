package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** IM-03/05 持久化 AI 转人工队列；任务去重、认领、解决与审计均在服务端完成。 */
@Service
public class AiHandoffService {

    private static final Set<String> STATUSES = Set.of("OPEN", "CLAIMED", "RESOLVED", "IGNORED", "ALL");
    private static final Set<String> RESOLUTIONS = Set.of("RESOLVED", "IGNORED");
    private static final Map<String, String> REASON_LABELS = Map.ofEntries(
            Map.entry("BUYER_REQUESTED_HUMAN", "买家要求人工客服"),
            Map.entry("SENSITIVE_OR_HIGH_RISK", "敏感或高风险问题"),
            Map.entry("NO_REPLY_STRATEGY", "没有可用回复策略"),
            Map.entry("AI_NO_SAFE_ANSWER", "AI 未生成可安全发送的内容"),
            Map.entry("LOW_CONFIDENCE", "知识命中置信度不足"),
            Map.entry("AI_UNAVAILABLE", "AI 服务不可用或超时"),
            Map.entry("MESSAGE_OUTCOME_UNKNOWN", "消息发送结果未知"),
            Map.entry("REPLY_PREPARATION_FAILED", "回复准备失败")
    );

    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final Supplier<String> openAttemptTokenSupplier;

    @Autowired
    public AiHandoffService(JdbcTemplate jdbcTemplate,
                            AccountAccessService accountAccessService,
                            OperationLogService operationLogService,
                            ObjectMapper objectMapper) {
        this(jdbcTemplate, accountAccessService, operationLogService, objectMapper,
                () -> UUID.randomUUID().toString());
    }

    AiHandoffService(JdbcTemplate jdbcTemplate,
                     AccountAccessService accountAccessService,
                     OperationLogService operationLogService,
                     ObjectMapper objectMapper,
                     Supplier<String> openAttemptTokenSupplier) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
        this.openAttemptTokenSupplier = openAttemptTokenSupplier;
    }

    @Transactional
    public Map<String, Object> open(OpenCommand command) {
        if (command == null) throw new BusinessException(400, "转人工参数不能为空");
        Long accountId = positive(command.accountId(), "账号ID");
        accountAccessService.requireAccess(accountId);
        String sessionId = required(command.sessionId(), "会话ID", 100);
        String reasonCode = normalizedReason(command.reasonCode());
        String requestId = required(command.requestId(), "requestId", 80);
        String dedupeKey = required(command.dedupeKey(), "去重键", 191);
        BigDecimal confidence = confidence(command.confidenceScore());
        String openAttemptToken = required(openAttemptTokenSupplier.get(), "创建尝试标识", 64);
        jdbcTemplate.update("""
                INSERT INTO xianyu_ai_handoff_task
                (tenant_id,xianyu_account_id,session_id,xy_goods_id,buyer_user_id,source_reply_record_id,
                 reason_code,reason_detail,priority,status,confidence_score,model_name,dedupe_key,request_id,open_attempt_token)
                VALUES (?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, tenant(), accountId, sessionId, trim(command.goodsId(), 100), trim(command.buyerUserId(), 100),
                command.sourceReplyRecordId(), reasonCode, trim(command.reasonDetail(), 500),
                priority(reasonCode), confidence, trim(command.modelName(), 100), dedupeKey, requestId, openAttemptToken);
        Map<String, Object> task = taskByDedupe(dedupeKey);
        Long taskId = number(task.get("id"));
        jdbcTemplate.update("""
                INSERT INTO conversation_assignment
                    (tenant_id,xianyu_account_id,session_id,buyer_user_id,first_message_time,last_message_time,sla_due_time)
                VALUES (?,?,?,?,NOW(3),NOW(3),NOW(3))
                ON DUPLICATE KEY UPDATE buyer_user_id=COALESCE(buyer_user_id,VALUES(buyer_user_id)),
                    last_message_time=GREATEST(COALESCE(last_message_time,VALUES(last_message_time)),VALUES(last_message_time))
                """, tenant(), accountId, sessionId, trim(command.buyerUserId(), 100));
        jdbcTemplate.update("""
                UPDATE conversation_assignment
                   SET auto_reply_state='HUMAN_REQUIRED',handoff_status=?,handoff_reason_code=?,
                       handoff_task_id=?,handoff_created_time=COALESCE(handoff_created_time,NOW(3)),
                       priority=IF(priority IN ('LOW','NORMAL'),?,priority)
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, task.get("status"), reasonCode, taskId, priority(reasonCode), tenant(), accountId, sessionId);
        if (command.sourceReplyRecordId() != null) {
            jdbcTemplate.update("""
                    UPDATE xianyu_goods_auto_reply_record
                       SET decision_state='HUMAN_REQUIRED',confidence_score=?,model_name=?,
                           processing_duration_ms=?,handoff_reason_code=?
                     WHERE tenant_id=? AND id=? AND xianyu_account_id=?
                    """, confidence, trim(command.modelName(), 100), nonNegative(command.processingDurationMs()),
                    reasonCode, tenant(), command.sourceReplyRecordId(), accountId);
        }
        auditOnce("AI_HANDOFF_OPEN", taskId, accountId, sessionId, requestId,
                Map.of("reasonCode", reasonCode, "dedupeKey", dedupeKey), "LOCAL_SUCCESS", null);
        // MySQL affected-row semantics vary with connector flags for ON DUPLICATE KEY UPDATE.
        // Compare the persisted ownership token instead so first insert vs replay is deterministic,
        // including concurrent opens using the same dedupe key.
        task.put("idempotentReplay", !openAttemptToken.equals(task.remove("openAttemptToken")));
        task.put("reasonLabel", reasonLabel(reasonCode));
        return task;
    }

    public Map<String, Object> list(String status, Long accountId, String search, Integer limit) {
        if (accountId != null) accountAccessService.requireAccess(accountId);
        String normalized = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) throw new BusinessException(400, "转人工状态无效");
        int safeLimit = Math.max(1, Math.min(limit == null ? 200 : limit, 500));
        StringBuilder where = new StringBuilder(" WHERE task.tenant_id=?").append(scope("task"));
        ArrayList<Object> args = new ArrayList<>();
        args.add(tenant());
        if (!"ALL".equals(normalized)) { where.append(" AND task.status=?"); args.add(normalized); }
        if (accountId != null) { where.append(" AND task.xianyu_account_id=?"); args.add(accountId); }
        String keyword = trim(search, 200);
        if (keyword != null) {
            where.append(" AND (task.session_id LIKE ? OR task.xy_goods_id LIKE ? OR task.buyer_user_id LIKE ? OR task.reason_detail LIKE ?)");
            String like = "%" + keyword + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        args.add(safeLimit);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                SELECT task.id,task.xianyu_account_id accountId,account.account_note accountName,
                       task.session_id sessionId,task.xy_goods_id goodsId,task.buyer_user_id buyerUserId,
                       task.source_reply_record_id sourceReplyRecordId,task.reason_code reasonCode,
                       task.reason_detail reasonDetail,task.priority,task.status,
                       task.confidence_score confidenceScore,task.model_name modelName,
                       task.request_id requestId,task.claimed_by claimedBy,task.claimed_username claimedUsername,
                       task.claimed_time claimedTime,task.resolved_by resolvedBy,
                       task.resolved_username resolvedUsername,task.resolved_time resolvedTime,
                       task.resolution_note resolutionNote,task.created_time createdTime,task.updated_time updatedTime
                  FROM xianyu_ai_handoff_task task
                  JOIN xianyu_account account ON account.id=task.xianyu_account_id AND account.tenant_id=task.tenant_id
                """ + where + " ORDER BY FIELD(task.priority,'URGENT','HIGH','NORMAL','LOW'),task.created_time ASC LIMIT ?", args.toArray());
        records.forEach(row -> row.put("reasonLabel", reasonLabel(String.valueOf(row.get("reasonCode")))));
        return Map.of("records", records, "returnedCount", records.size(),
                "dataNotice", "人工任务为服务端持久化状态；发送结果未知的任务必须先核对会话，不能盲目重发。");
    }

    @Transactional
    public Map<String, Object> claim(Long id, ActionCommand command) {
        Map<String, Object> task = requireTask(id);
        String requestId = required(command == null ? null : command.requestId(), "requestId", 80);
        if ("CLAIMED".equals(task.get("status")) && UserContext.getUserId() != null
                && UserContext.getUserId().equals(number(task.get("claimedBy")))) {
            task.put("idempotentReplay", true);
            return task;
        }
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_ai_handoff_task SET status='CLAIMED',claimed_by=?,claimed_username=?,claimed_time=NOW(3)
                 WHERE tenant_id=? AND id=? AND xianyu_account_id=? AND status='OPEN'
                """, UserContext.getUserId(), UserContext.getUsername(), tenant(), id, number(task.get("accountId")));
        if (updated == 0) throw new BusinessException(409, "任务已被其他成员认领或已结束");
        jdbcTemplate.update("""
                UPDATE conversation_assignment SET handoff_status='CLAIMED',status='IN_PROGRESS',
                       assigned_user_id=?,assigned_username=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=? AND handoff_task_id=?
                """, UserContext.getUserId(), UserContext.getUsername(), tenant(), number(task.get("accountId")),
                task.get("sessionId"), id);
        auditOnce("AI_HANDOFF_CLAIM", id, number(task.get("accountId")), String.valueOf(task.get("sessionId")),
                requestId, Map.of(), "LOCAL_SUCCESS", null);
        Map<String, Object> result = requireTask(id);
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String, Object> resolve(Long id, ActionCommand command) {
        Map<String, Object> task = requireTask(id);
        String requestId = required(command == null ? null : command.requestId(), "requestId", 80);
        String resolution = command.status() == null ? "RESOLVED" : command.status().trim().toUpperCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(resolution)) throw new BusinessException(400, "只能将任务标记为已解决或已忽略");
        String note = trim(command.note(), 1000);
        if ("IGNORED".equals(resolution) && note == null) throw new BusinessException(400, "忽略任务必须填写原因");
        if (RESOLUTIONS.contains(String.valueOf(task.get("status")))) {
            task.put("idempotentReplay", true);
            return task;
        }
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_ai_handoff_task SET status=?,resolution_note=?,resolved_by=?,resolved_username=?,resolved_time=NOW(3)
                 WHERE tenant_id=? AND id=? AND xianyu_account_id=? AND status IN ('OPEN','CLAIMED')
                """, resolution, note, UserContext.getUserId(), UserContext.getUsername(), tenant(), id,
                number(task.get("accountId")));
        if (updated == 0) throw new BusinessException(409, "任务状态已变化，请刷新后重试");
        Long remaining = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_ai_handoff_task
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=? AND status IN ('OPEN','CLAIMED')
                """, Long.class, tenant(), number(task.get("accountId")), task.get("sessionId"));
        if (remaining != null && remaining == 0) {
            jdbcTemplate.update("""
                    UPDATE conversation_assignment SET handoff_status='NONE',handoff_reason_code=NULL,
                           handoff_task_id=NULL,handoff_created_time=NULL,
                           auto_reply_state=CASE WHEN manual_takeover_state='MANUAL' AND manual_takeover_until>NOW(3)
                                THEN 'PAUSED_MANUAL' ELSE 'AUTO_READY' END
                     WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                    """, tenant(), number(task.get("accountId")), task.get("sessionId"));
        }
        auditOnce("AI_HANDOFF_" + resolution, id, number(task.get("accountId")), String.valueOf(task.get("sessionId")),
                requestId, Map.of("status", resolution, "note", note == null ? "" : note), "LOCAL_SUCCESS", null);
        Map<String, Object> result = requireTask(id);
        result.put("idempotentReplay", false);
        return result;
    }

    public List<Map<String, Object>> decisions(Long accountId, String sessionId, int limit) {
        accountAccessService.requireAccess(accountId);
        return jdbcTemplate.queryForList("""
                SELECT record.id replyRecordId,record.pnm_id messageId,record.buyer_message buyerMessage,
                       record.reply_content replyContent,record.reply_type replyType,
                       record.matched_keyword matchedKeyword,record.selected_rule_id selectedRuleId,
                       record.selected_content_id selectedContentId,record.trigger_context triggerContext,
                       record.state,record.decision_state decisionState,record.confidence_score confidenceScore,
                       record.decision_trace_json decisionTraceJson,record.safety_verdict safetyVerdict,
                       record.model_name modelName,record.processing_duration_ms processingDurationMs,
                       record.handoff_reason_code handoffReasonCode,record.last_error_code lastErrorCode,
                       record.last_error_message lastErrorMessage,record.create_time createdTime
                  FROM xianyu_goods_auto_reply_record record
                 WHERE record.tenant_id=? AND record.xianyu_account_id=? AND record.s_id=?
                 ORDER BY record.create_time DESC,record.id DESC LIMIT ?
                """, tenant(), accountId, required(sessionId, "会话ID", 100), Math.max(1, Math.min(limit, 100)));
    }

    public boolean hasPending(Long accountId, String sessionId) {
        if (accountId == null || sessionId == null || sessionId.isBlank() || TenantContext.get() == null) return false;
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_ai_handoff_task
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=? AND status IN ('OPEN','CLAIMED')
                """, Long.class, tenant(), accountId, sessionId);
        return count != null && count > 0;
    }

    /** 由消息核对状态机关闭对应的结果未知任务；没有任务时保持幂等。 */
    @Transactional
    public void resolveMessageOutcome(String sendRequestId, String resolutionRequestId, String note) {
        List<Map<String,Object>> tasks = jdbcTemplate.queryForList("""
                SELECT id FROM xianyu_ai_handoff_task
                 WHERE tenant_id=? AND dedupe_key=? AND status IN ('OPEN','CLAIMED')
                """, tenant(), "MESSAGE_SEND:" + tenant() + ":" + sendRequestId);
        if (tasks.isEmpty()) return;
        resolve(number(tasks.getFirst().get("id")),
                new ActionCommand("RESOLVED", trim(note, 1000), resolutionRequestId));
    }

    private Map<String, Object> requireTask(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "任务ID无效");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,xianyu_account_id accountId,session_id sessionId,status,claimed_by claimedBy,
                       reason_code reasonCode,request_id requestId FROM xianyu_ai_handoff_task
                 WHERE tenant_id=? AND id=?
                """, tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "人工接管任务不存在或无权访问");
        accountAccessService.requireAccess(number(rows.getFirst().get("accountId")));
        return new LinkedHashMap<>(rows.getFirst());
    }

    private Map<String, Object> taskByDedupe(String dedupeKey) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT id,xianyu_account_id accountId,session_id sessionId,status,reason_code reasonCode,
                       request_id requestId,open_attempt_token openAttemptToken,created_time createdTime
                  FROM xianyu_ai_handoff_task
                 WHERE tenant_id=? AND dedupe_key=?
                """, tenant(), dedupeKey));
    }

    private void auditOnce(String type, Long taskId, Long accountId, String sessionId, String requestId,
                           Object request, String outcome, String error) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_operation_log WHERE tenant_id=? AND request_id=? AND operation_type=?
                """, Long.class, tenant(), requestId, type);
        if (count != null && count > 0) return;
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId); log.setOperationType(type); log.setOperationModule("集成客服");
        log.setOperationDesc("AI 转人工任务：" + type); log.setOperationStatus(error == null ? 1 : 0);
        log.setTargetType("AI_HANDOFF"); log.setTargetId(String.valueOf(taskId));
        log.setRequestId(requestId); log.setIdempotencyKey(requestId); log.setOutcomeState(outcome);
        log.setDataSource("LOCAL"); log.setRequestParams(json(request)); log.setResponseResult(json(Map.of("sessionId", sessionId)));
        log.setErrorMessage(error); operationLogService.log(log);
    }

    private String scope(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1=0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private Long positive(Long value, String label) {
        if (value == null || value <= 0) throw new BusinessException(400, label + "无效");
        return value;
    }

    private String required(String value, String label, int max) {
        String normalized = trim(value, max);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (value.trim().length() > max) throw new BusinessException(400, label + "不能超过" + max + "个字符");
        return normalized;
    }

    private String normalizedReason(String value) {
        String normalized = required(value, "转人工原因", 40).toUpperCase(Locale.ROOT);
        if (!REASON_LABELS.containsKey(normalized)) throw new BusinessException(400, "转人工原因无效");
        return normalized;
    }

    private String priority(String reason) {
        return Set.of("SENSITIVE_OR_HIGH_RISK", "MESSAGE_OUTCOME_UNKNOWN").contains(reason) ? "URGENT" : "HIGH";
    }

    private String reasonLabel(String reason) { return REASON_LABELS.getOrDefault(reason, reason); }
    private String trim(String value, int max) { if (value == null || value.trim().isEmpty()) return null; String n=value.trim(); return n.substring(0, Math.min(max,n.length())); }
    private Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
    private Long nonNegative(Long value) { return value == null ? null : Math.max(0, value); }
    private BigDecimal confidence(Double value) {
        if (value == null) return null;
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new BusinessException(400, "置信度必须在0到1之间");
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }

    public record OpenCommand(Long accountId, String sessionId, String goodsId, String buyerUserId,
                              Long sourceReplyRecordId, String reasonCode, String reasonDetail,
                              Double confidenceScore, String modelName, Long processingDurationMs,
                              String dedupeKey, String requestId) {}

    public record ActionCommand(String status, String note, String requestId) {}
}
