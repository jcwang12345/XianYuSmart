package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.exception.DeliveryUncertainException;
import com.xianyusmart.service.reply.HumanTakeoverManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** IM-01/02 会话收件箱与可追踪发送，不把超时误报为失败后重发。 */
@Service
public class MessageWorkspaceService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final ConversationAssignmentService assignmentService;
    private final WebSocketService webSocketService;
    private final SentMessageSaveService sentMessageSaveService;
    private final HumanTakeoverManager takeoverManager;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public MessageWorkspaceService(JdbcTemplate jdbcTemplate,
                                   AccountAccessService accountAccessService,
                                   ConversationAssignmentService assignmentService,
                                   WebSocketService webSocketService,
                                   SentMessageSaveService sentMessageSaveService,
                                   HumanTakeoverManager takeoverManager,
                                   OperationLogService operationLogService,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.assignmentService = assignmentService;
        this.webSocketService = webSocketService;
        this.sentMessageSaveService = sentMessageSaveService;
        this.takeoverManager = takeoverManager;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> conversations(String status, Boolean unreadOnly, Long accountId,
                                             String search, Integer limit) {
        return conversations(status, unreadOnly, accountId, search, null, null, limit);
    }

    public Map<String, Object> conversations(String status, Boolean unreadOnly, Long accountId,
                                             String search, Boolean pinnedOnly, String keywordFlag, Integer limit) {
        if (accountId != null) accountAccessService.requireAccess(accountId);
        assignmentService.refresh();
        String normalizedStatus = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "IN_PROGRESS", "CLOSED", "ALL").contains(normalizedStatus)) {
            throw new BusinessException(400, "会话状态无效");
        }
        int safeLimit = Math.max(1, Math.min(limit == null ? 200 : limit, 500));
        StringBuilder where = new StringBuilder(" WHERE assignment.tenant_id=?").append(scope("assignment"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenant());
        if (!"ALL".equals(normalizedStatus)) { where.append(" AND assignment.status=?"); args.add(normalizedStatus); }
        if (Boolean.TRUE.equals(unreadOnly)) where.append(" AND assignment.unread_count>0");
        if (Boolean.TRUE.equals(pinnedOnly)) where.append(" AND assignment.pinned=1");
        String normalizedFlag = trim(keywordFlag);
        if (normalizedFlag != null) { where.append(" AND assignment.keyword_flag=?"); args.add(normalizedFlag); }
        if (accountId != null) { where.append(" AND assignment.xianyu_account_id=?"); args.add(accountId); }
        String keyword = trim(search);
        if (keyword != null) {
            where.append(" AND (assignment.session_id LIKE ? OR assignment.buyer_user_id LIKE ? OR buyer.buyer_user_name LIKE ?")
                    .append(" OR EXISTS (SELECT 1 FROM xianyu_chat_message m WHERE m.tenant_id=assignment.tenant_id")
                    .append(" AND m.xianyu_account_id=assignment.xianyu_account_id AND m.s_id=assignment.session_id AND m.msg_content LIKE ?)")
                    .append(" OR EXISTS (SELECT 1 FROM xianyu_goods_order o WHERE o.tenant_id=assignment.tenant_id")
                    .append(" AND o.xianyu_account_id=assignment.xianyu_account_id AND o.sid=assignment.session_id")
                    .append(" AND (o.order_id LIKE ? OR o.goods_title LIKE ?)))");
            String like = "%" + keyword + "%";
            for (int i = 0; i < 6; i++) args.add(like);
        }
        args.add(safeLimit);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                SELECT assignment.id,assignment.xianyu_account_id accountId,account.account_note accountName,
                       assignment.session_id sessionId,assignment.buyer_user_id buyerUserId,
                       buyer.buyer_user_name buyerName,buyer.tags_json buyerTagsJson,buyer.note buyerNote,
                       assignment.unread_count unreadCount,assignment.status,assignment.priority,
                       assignment.pinned,assignment.keyword_flag keywordFlag,
                       assignment.customer_note customerNote,assignment.customer_blacklisted customerBlacklisted,
                       assignment.assigned_user_id assignedUserId,assignment.assigned_username assignedUsername,
                       assignment.manual_takeover_state manualTakeoverState,
                       assignment.manual_takeover_until manualTakeoverUntil,
                       assignment.auto_reply_state autoReplyState,
                       assignment.history_sync_status historySyncStatus,
                       assignment.history_coverage_status historyCoverageStatus,
                       assignment.history_last_synced_time historyLastSyncedTime,
                       assignment.history_last_error historyLastError,
                       assignment.first_message_time firstMessageTime,
                       assignment.first_response_time firstResponseTime,
                       assignment.last_message_time lastMessageTime,assignment.sla_due_time slaDueTime,
                       CASE WHEN assignment.first_response_time IS NULL AND assignment.sla_due_time<NOW(3) THEN 1 ELSE 0 END slaBreached,
                       (SELECT m.msg_content FROM xianyu_chat_message m
                         WHERE m.tenant_id=assignment.tenant_id AND m.xianyu_account_id=assignment.xianyu_account_id
                           AND m.s_id=assignment.session_id ORDER BY m.message_time DESC,m.id DESC LIMIT 1) lastMessage,
                       (SELECT o.order_id FROM xianyu_goods_order o
                         WHERE o.tenant_id=assignment.tenant_id AND o.xianyu_account_id=assignment.xianyu_account_id
                           AND o.sid=assignment.session_id ORDER BY o.create_time DESC,o.id DESC LIMIT 1) relatedOrderId,
                       (SELECT o.delivery_status FROM xianyu_goods_order o
                         WHERE o.tenant_id=assignment.tenant_id AND o.xianyu_account_id=assignment.xianyu_account_id
                           AND o.sid=assignment.session_id ORDER BY o.create_time DESC,o.id DESC LIMIT 1) relatedOrderStatus,
                       (SELECT m.xy_goods_id FROM xianyu_chat_message m
                         WHERE m.tenant_id=assignment.tenant_id AND m.xianyu_account_id=assignment.xianyu_account_id
                           AND m.s_id=assignment.session_id AND m.xy_goods_id IS NOT NULL
                         ORDER BY m.message_time DESC,m.id DESC LIMIT 1) relatedGoodsId
                  FROM conversation_assignment assignment
                  JOIN xianyu_account account ON account.id=assignment.xianyu_account_id AND account.tenant_id=assignment.tenant_id
                  LEFT JOIN xianyu_buyer_profile buyer ON buyer.tenant_id=assignment.tenant_id
                    AND buyer.xianyu_account_id=assignment.xianyu_account_id AND buyer.buyer_user_id=assignment.buyer_user_id
                """ + where + " ORDER BY slaBreached DESC,assignment.unread_count DESC,assignment.last_message_time DESC LIMIT ?", args.toArray());
        records.forEach(row -> row.put("buyerTags", readJson((String) row.remove("buyerTagsJson"))));
        long unread = records.stream().map(row -> row.get("unreadCount"))
                .filter(Number.class::isInstance).map(Number.class::cast).mapToLong(Number::longValue).sum();
        return Map.of("records", records, "returnedCount", records.size(), "unreadInResult", unread,
                "dataNotice", "未读状态保存在服务端；历史同步覆盖状态按会话展示，PARTIAL 不代表完整历史。" );
    }

    public Map<String, Object> detail(Long accountId, String sessionId, Integer limit, Integer offset) {
        requireConversation(accountId, sessionId);
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        List<Map<String, Object>> messages = jdbcTemplate.queryForList("""
                SELECT id,pnm_id messageId,content_type contentType,msg_content content,
                       sender_user_name senderName,sender_user_id senderId,xy_goods_id goodsId,
                       reminder_url reminderUrl,message_time messageTime,create_time storedAt
                  FROM xianyu_chat_message
                 WHERE tenant_id=? AND xianyu_account_id=? AND s_id=?
                 ORDER BY message_time DESC,id DESC LIMIT ? OFFSET ?
                """, tenant(), accountId, sessionId, safeLimit, safeOffset);
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("""
                SELECT id orderRecordId,order_id orderId,buyer_user_name buyerName,xy_goods_id goodsId,
                       goods_title goodsTitle,sku_name skuName,order_amount amount,currency_code currency,
                       delivery_status deliveryStatus,refund_status refundStatus,create_time createdTime
                  FROM xianyu_goods_order
                 WHERE tenant_id=? AND xianyu_account_id=? AND sid=? ORDER BY create_time DESC,id DESC LIMIT 20
                """, tenant(), accountId, sessionId);
        List<Map<String, Object>> sends = jdbcTemplate.queryForList("""
                SELECT request_id requestId,content_type contentType,content_excerpt contentExcerpt,
                       outcome_state outcomeState,platform_ack_code platformAckCode,error_message errorMessage,
                       operator_username operatorUsername,created_time createdTime,updated_time updatedTime
                  FROM xianyu_message_send_attempt
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=? ORDER BY created_time DESC LIMIT 100
                """, tenant(), accountId, sessionId);
        return Map.of("accountId", accountId, "sessionId", sessionId, "messages", messages,
                "relatedOrders", orders, "sendAttempts", sends,
                "historyPage", Map.of("limit", safeLimit, "offset", safeOffset));
    }

    public void markRead(Long accountId, String sessionId) {
        accountAccessService.requireAccess(accountId);
        assignmentService.markRead(accountId, required(sessionId, "会话ID", 100));
    }

    @Transactional
    public Map<String, Object> takeover(Long accountId, String sessionId, String goodsId, Integer minutes) {
        requireConversation(accountId, sessionId);
        int safeMinutes = Math.max(1, Math.min(minutes == null ? 10 : minutes, 1440));
        takeoverManager.takeover(accountId, trim(goodsId), sessionId, safeMinutes);
        jdbcTemplate.update("""
                UPDATE conversation_assignment SET manual_takeover_state='MANUAL',
                       manual_takeover_until=DATE_ADD(NOW(3),INTERVAL ? MINUTE),auto_reply_state='PAUSED_MANUAL'
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, safeMinutes, tenant(), accountId, sessionId);
        return Map.of("manualTakeoverState", "MANUAL", "minutes", safeMinutes);
    }

    /** 会话运营字段保存于服务端，并把黑名单同步为真正的自动化拦截规则。 */
    @Transactional
    public Map<String, Object> updateConversation(ConversationUpdate command) {
        if (command == null) throw new BusinessException(400, "会话设置不能为空");
        Long accountId = command.accountId();
        String sessionId = required(command.sessionId(), "会话ID", 100);
        String requestId = required(command.requestId(), "requestId", 80);
        requireConversation(accountId, sessionId);
        String flag = trim(command.keywordFlag());
        if (flag != null && flag.length() > 100) throw new BusinessException(400, "关键词标记不能超过100个字符");
        String note = trim(command.customerNote());
        if (note != null && note.length() > 500) throw new BusinessException(400, "客户备注不能超过500个字符");
        boolean pinned = Boolean.TRUE.equals(command.pinned());
        boolean blacklisted = Boolean.TRUE.equals(command.blacklisted());
        Map<String, Object> conversation = jdbcTemplate.queryForMap("""
                SELECT buyer_user_id buyerUserId FROM conversation_assignment
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, tenant(), accountId, sessionId);
        jdbcTemplate.update("""
                UPDATE conversation_assignment SET pinned=?,keyword_flag=?,customer_note=?,customer_blacklisted=?
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, pinned ? 1 : 0, flag, note, blacklisted ? 1 : 0, tenant(), accountId, sessionId);
        String buyerId = trim((String) conversation.get("buyerUserId"));
        if (buyerId != null && blacklisted) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_buyer_profile
                    (tenant_id,xianyu_account_id,buyer_user_id,note,automation_blocked,blocked_reason,last_interaction_time)
                    VALUES (?,?,?,?,1,?,NOW(3))
                    ON DUPLICATE KEY UPDATE note=COALESCE(VALUES(note),note),automation_blocked=1,
                     blocked_reason=VALUES(blocked_reason)
                    """, tenant(), accountId, buyerId, note, "[会话] 已加入客户黑名单");
        } else if (buyerId != null) {
            jdbcTemplate.update("""
                    UPDATE xianyu_buyer_profile SET automation_blocked=0,blocked_reason=NULL
                     WHERE tenant_id=? AND xianyu_account_id=? AND buyer_user_id=?
                       AND blocked_reason LIKE '[会话]%'
                    """, tenant(), accountId, buyerId);
        }
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId); log.setOperationType("CONVERSATION_UPDATE");
        log.setOperationModule("集成客服"); log.setOperationDesc("更新会话置顶、标记、备注与黑名单");
        log.setOperationStatus(1); log.setTargetType("CONVERSATION"); log.setTargetId(sessionId);
        log.setRequestId(requestId); log.setIdempotencyKey(requestId); log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL"); log.setFieldDiffJson(json(command)); operationLogService.log(log);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("accountId",accountId); result.put("sessionId",sessionId); result.put("pinned",pinned);
        result.put("keywordFlag",flag); result.put("customerNote",note); result.put("blacklisted",blacklisted);
        result.put("automationBlocked",blacklisted); result.put("requestId",requestId);
        return result;
    }

    public Map<String, Object> sendText(SendCommand command) {
        String text = required(command == null ? null : command.content(), "消息内容", 2000);
        return send(command, "TEXT", text, 0, 0);
    }

    public Map<String, Object> sendImage(SendCommand command) {
        String image = required(command == null ? null : command.content(), "图片地址", 2000);
        if (!image.startsWith("https://")) throw new BusinessException(400, "图片消息必须使用已上传平台的 HTTPS 地址");
        int width = command.width() == null || command.width() <= 0 ? 800 : Math.min(command.width(), 4096);
        int height = command.height() == null || command.height() <= 0 ? 800 : Math.min(command.height(), 4096);
        return send(command, "IMAGE", image, width, height);
    }

    @Transactional(noRollbackFor = DeliveryUncertainException.class)
    protected Map<String, Object> send(SendCommand command, String type, String content, int width, int height) {
        if (command == null) throw new BusinessException(400, "消息参数不能为空");
        Long accountId = command.accountId();
        String sessionId = required(command.sessionId(), "会话ID", 100);
        String recipient = required(command.recipientUserId(), "接收方ID", 100);
        String requestId = required(command.requestId(), "requestId", 80);
        requireConversation(accountId, sessionId);
        List<Map<String, Object>> replay = jdbcTemplate.queryForList("""
                SELECT outcome_state outcomeState,error_message errorMessage,updated_time updatedTime
                  FROM xianyu_message_send_attempt WHERE tenant_id=? AND request_id=?
                """, tenant(), requestId);
        if (!replay.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>(replay.getFirst());
            result.put("requestId", requestId);
            result.put("idempotentReplay", true);
            return result;
        }
        jdbcTemplate.update("""
                INSERT INTO xianyu_message_send_attempt
                (tenant_id,xianyu_account_id,session_id,recipient_user_id,xy_goods_id,content_type,
                 content_sha256,content_excerpt,request_id,idempotency_key,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant(), accountId, sessionId, recipient, trim(command.goodsId()), type,
                sha256(content), excerpt(content), requestId, requestId, UserContext.getUserId(), UserContext.getUsername());
        if (!webSocketService.isConnected(accountId)) {
            return finishAttempt(accountId, requestId, "FAILED", "账号实时连接未建立", command, false);
        }
        try {
            boolean acknowledged = "IMAGE".equals(type)
                    ? webSocketService.sendImageMessageWithResult(accountId, sessionId, recipient, content, width, height)
                    : webSocketService.sendMessageWithResult(accountId, sessionId, recipient, content);
            if (!acknowledged) return finishAttempt(accountId, requestId, "FAILED", "平台明确返回发送失败", command, false);
            if ("IMAGE".equals(type)) sentMessageSaveService.saveManualImageReply(accountId, sessionId, recipient, content, command.goodsId());
            else sentMessageSaveService.saveManualReply(accountId, sessionId, recipient, content, command.goodsId());
            takeoverManager.takeover(accountId, trim(command.goodsId()), sessionId, 10);
            return finishAttempt(accountId, requestId, "SENT", null, command, true);
        } catch (DeliveryUncertainException e) {
            return finishAttempt(accountId, requestId, "UNKNOWN", e.getMessage(), command, false);
        } catch (Exception e) {
            return finishAttempt(accountId, requestId, "FAILED", e.getMessage(), command, false);
        }
    }

    private Map<String, Object> finishAttempt(Long accountId, String requestId, String outcome,
                                              Object request, boolean success) {
        return finishAttempt(accountId, requestId, outcome, null, request, success);
    }

    private Map<String, Object> finishAttempt(Long accountId, String requestId, String outcome,
                                              String error, Object request, boolean success) {
        jdbcTemplate.update("""
                UPDATE xianyu_message_send_attempt SET outcome_state=?,platform_ack_code=?,error_message=?
                 WHERE tenant_id=? AND request_id=?
                """, outcome, success ? "200" : null, limit(error, 500), tenant(), requestId);
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType("MESSAGE_SEND");
        log.setOperationModule("集成客服");
        log.setOperationDesc("发送客服消息：" + outcome);
        log.setOperationStatus(success ? 1 : "UNKNOWN".equals(outcome) ? 2 : 0);
        log.setTargetType("CONVERSATION");
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource("PLATFORM_WEBSOCKET");
        if (request instanceof SendCommand command) {
            log.setRequestParams(json(Map.of(
                    "accountId", command.accountId(),
                    "sessionId", command.sessionId(),
                    "recipientUserId", command.recipientUserId(),
                    "goodsId", command.goodsId() == null ? "" : command.goodsId(),
                    "contentSha256", sha256(command.content()),
                    "requestId", command.requestId())));
        } else {
            log.setRequestParams("{}");
        }
        log.setErrorMessage(limit(error, 500));
        operationLogService.log(log);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("outcomeState", outcome);
        result.put("idempotentReplay", false);
        result.put("error", error);
        result.put("recoveryHint", "UNKNOWN".equals(outcome)
                ? "请先查看当前会话是否已出现该消息，确认前不要重复发送" : null);
        return result;
    }

    private void requireConversation(Long accountId, String sessionId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        accountAccessService.requireAccess(accountId);
        String normalized = required(sessionId, "会话ID", 100);
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM conversation_assignment
                 WHERE tenant_id=? AND xianyu_account_id=? AND session_id=?
                """, Long.class, tenant(), accountId, normalized);
        if (count == null || count == 0) throw new BusinessException(404, "会话不存在或尚未同步");
    }

    private String scope(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1=0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".xianyu_account_id IN (" + ids + ")";
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String required(String value, String label, int max) {
        String normalized = trim(value);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (normalized.length() > max) throw new BusinessException(400, label + "不能超过" + max + "个字符");
        return normalized;
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法生成消息摘要", e); }
    }

    private String excerpt(String value) { return value.substring(0, Math.min(200, value.length())); }
    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String limit(String value, int max) { String normalized=trim(value); return normalized==null?null:normalized.substring(0,Math.min(max,normalized.length())); }
    private Object readJson(String value) { try { return value==null?null:objectMapper.readValue(value,Object.class); } catch(Exception e){ return value; } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch(Exception e){ return "{}"; } }

    public record SendCommand(Long accountId, String sessionId, String recipientUserId, String goodsId,
                              String content, Integer width, Integer height, String requestId) {}
    public record ConversationUpdate(Long accountId,String sessionId,Boolean pinned,String keywordFlag,
                                     String customerNote,Boolean blacklisted,String requestId) {}
}
