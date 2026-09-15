package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 站内通知是真实事件收件箱；已读与已处理是两个独立状态。 */
@Service
public class NotificationInboxService {

    private static final Set<String> HANDLING = Set.of("UNHANDLED", "IN_PROGRESS", "RESOLVED", "IGNORED");
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final ObjectMapper objectMapper;

    public NotificationInboxService(JdbcTemplate jdbcTemplate,
                                    AccountAccessService accountAccessService,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String record(String eventType, Long accountId, String title, String content, Map<String, Object> input) {
        Map<String, Object> data = publicData(input);
        return record(UUID.randomUUID().toString(), dedupe(eventType, accountId, data),
                eventType, accountId, title, content, data);
    }

    /** Persist one canonical business event id shared by inbox and every channel delivery. */
    @Transactional
    public String record(String proposedEventId, String dedupeKey, String eventType, Long accountId,
                         String title, String content, Map<String, Object> input) {
        Long tenantId = tenant();
        Map<String, Object> data = publicData(input);
        String objectType = objectType(eventType, data);
        String objectId = objectId(data);
        String eventId = trim(proposedEventId);
        if (eventId == null || eventId.length() > 64) throw new BusinessException(400, "通知eventId无效");
        String dedupe = trim(dedupeKey);
        if (dedupe == null) throw new BusinessException(400, "通知去重键不能为空");
        jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_notification_event
                (tenant_id,event_id,event_type,xianyu_account_id,business_object_type,business_object_id,
                 severity,title,content_summary,source,dedupe_key,target_route,data_json)
                VALUES (?,?,?,?,?,?,?,?,?,'SYSTEM_EVENT',?,?,?)
                """, tenantId, eventId, eventType, accountId, objectType, objectId, severity(eventType),
                limit(title, 200), limit(content, 1000), dedupe, route(eventType, accountId, data), json(data));
        String canonical = jdbcTemplate.queryForObject("""
                SELECT event_id FROM xianyu_notification_event
                 WHERE tenant_id=? AND event_type=? AND dedupe_key=?
                """, String.class, tenantId, eventType, dedupe);
        return canonical == null || canonical.isBlank() ? eventId : canonical;
    }

    public Map<String, Object> list(String view, Long accountId, String search, Integer page, Integer pageSize) {
        if (accountId != null) accountAccessService.requireAccess(accountId);
        String normalizedView = view == null || view.isBlank() ? "ALL" : view.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "UNREAD", "PENDING", "SENT").contains(normalizedView)) {
            throw new BusinessException(400, "通知视图无效");
        }
        int safePage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(100, pageSize);
        StringBuilder where = new StringBuilder(" WHERE event.tenant_id=?").append(scope("event"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenant());
        if (accountId != null) { where.append(" AND event.xianyu_account_id=?"); args.add(accountId); }
        if ("UNREAD".equals(normalizedView)) where.append(" AND event.read_time IS NULL");
        if ("PENDING".equals(normalizedView)) where.append(" AND event.handling_status IN ('UNHANDLED','IN_PROGRESS')");
        if ("SENT".equals(normalizedView)) where.append(" AND EXISTS (SELECT 1 FROM xianyu_notification_log log WHERE log.tenant_id=event.tenant_id AND log.event_id=event.event_id AND log.send_status=1)");
        String keyword = trim(search);
        if (keyword != null) { where.append(" AND (event.title LIKE ? OR event.content_summary LIKE ? OR event.business_object_id LIKE ?)"); String like="%"+keyword+"%"; args.add(like);args.add(like);args.add(like); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_notification_event event" + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((safePage - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                SELECT event.id,event.event_id eventId,event.event_type eventType,event.xianyu_account_id accountId,
                       account.account_note accountName,event.business_object_type businessObjectType,
                       event.business_object_id businessObjectId,event.severity,event.title,
                       event.content_summary contentSummary,event.source,event.target_route targetRoute,
                       event.data_json dataJson,event.read_time readTime,event.read_by readBy,
                       event.handling_status handlingStatus,event.handled_time handledTime,
                       event.handled_by handledBy,event.handling_note handlingNote,event.occurred_time occurredTime
                       ,(SELECT COUNT(*) FROM xianyu_notification_outbox outbox WHERE outbox.tenant_id=event.tenant_id AND outbox.event_id=event.event_id) deliveryTotal
                       ,(SELECT COUNT(*) FROM xianyu_notification_outbox outbox WHERE outbox.tenant_id=event.tenant_id AND outbox.event_id=event.event_id AND outbox.status='SENT') deliverySent
                       ,(SELECT COUNT(*) FROM xianyu_notification_outbox outbox WHERE outbox.tenant_id=event.tenant_id AND outbox.event_id=event.event_id AND outbox.status='FAILED') deliveryFailed
                  FROM xianyu_notification_event event
                  LEFT JOIN xianyu_account account ON account.id=event.xianyu_account_id AND account.tenant_id=event.tenant_id
                """ + where + " ORDER BY event.occurred_time DESC,event.id DESC LIMIT ? OFFSET ?", args.toArray());
        records.forEach(row -> {
            row.put("data", readJson((String) row.remove("dataJson")));
            long deliveryTotal = number(row.get("deliveryTotal"));
            long deliverySent = number(row.get("deliverySent"));
            long deliveryFailed = number(row.get("deliveryFailed"));
            row.put("deliveryStatus", deliveryTotal == 0 ? "NOT_CONFIGURED"
                    : deliverySent == deliveryTotal ? "SENT"
                    : deliveryFailed == deliveryTotal ? "FAILED"
                    : deliverySent > 0 ? "PARTIAL" : deliveryFailed > 0 ? "RETRYING_OR_FAILED" : "PENDING");
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        addPagination(result, total, safePage, size);
        result.put("statusMeaning", Map.of("read", "用户已查看", "handled", "业务问题已处理；已读不会自动变为已处理"));
        return result;
    }

    @Transactional
    public void markRead(Long id) {
        int updated = jdbcTemplate.update("UPDATE xianyu_notification_event event SET read_time=COALESCE(read_time,NOW(3)), read_by=COALESCE(read_by,?) WHERE event.id=? AND event.tenant_id=?" + scope("event"),
                UserContext.getUserId(), id, tenant());
        if (updated == 0) throw new BusinessException(404, "通知不存在或不在当前店铺权限范围");
    }

    @Transactional
    public void handle(Long id, String status, String note) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!HANDLING.contains(normalized)) throw new BusinessException(400, "处理状态无效");
        String safeNote = limit(note, 1000);
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_notification_event event
                   SET handling_status=?, handling_note=?,
                       handled_time=CASE WHEN ? IN ('RESOLVED','IGNORED') THEN NOW(3) ELSE NULL END,
                       handled_by=CASE WHEN ? IN ('RESOLVED','IGNORED') THEN ? ELSE NULL END
                 WHERE event.id=? AND event.tenant_id=?
                """ + scope("event"), normalized, safeNote, normalized, normalized, UserContext.getUserId(), id, tenant());
        if (updated == 0) throw new BusinessException(404, "通知不存在或不在当前店铺权限范围");
    }

    private void addPagination(Map<String, Object> result, Long total, int page, int size) {
        long count = total == null ? 0 : total;
        result.put("total", count);
        result.put("page", page);
        result.put("pageSize", size);
        result.put("totalPages", (int) Math.ceil((double) count / size));
    }

    private Map<String, Object> publicData(Map<String, Object> input) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (input != null) input.forEach((key, item) -> { if (!key.startsWith("_")) value.put(key, item); });
        return value;
    }

    private String dedupe(String type, Long accountId, Map<String, Object> data) {
        Object explicit = data.get("dedupeKey");
        if (explicit != null && !String.valueOf(explicit).isBlank()) return limit(String.valueOf(explicit), 191);
        String objectId = objectId(data);
        if (objectId != null) return limit("account:" + accountId + ":object:" + objectId, 191);
        long window = switch (type) {
            case "ACCOUNT_OFFLINE", "CREDENTIAL_EXPIRED", "ACCOUNT_VERIFICATION_REQUIRED" ->
                    System.currentTimeMillis() / Duration.ofHours(6).toMillis();
            default -> System.currentTimeMillis() / Duration.ofMinutes(5).toMillis();
        };
        return "account:" + accountId + ":window:" + window;
    }

    private String objectId(Map<String, Object> data) {
        for (String key : List.of("orderId", "refundId", "xyGoodsId", "riskId", "conversationId", "requestId")) {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private String objectType(String eventType, Map<String, Object> data) {
        if (data.containsKey("orderId")) return eventType.startsWith("REFUND") ? "REFUND" : "ORDER";
        if (data.containsKey("xyGoodsId")) return "PRODUCT";
        if (data.containsKey("conversationId")) return "CONVERSATION";
        if (eventType.startsWith("ACCOUNT") || eventType.startsWith("CREDENTIAL")) return "ACCOUNT";
        if (eventType.startsWith("PENALTY")) return "RISK";
        return "SYSTEM";
    }

    private String route(String eventType, Long accountId, Map<String, Object> data) {
        Object explicit = data.get("targetRoute");
        if (explicit != null) {
            String target = String.valueOf(explicit).trim();
            if (target.startsWith("/") && !target.startsWith("//") && target.length() <= 500) return target;
        }
        if (data.get("orderId") != null) return "/orders?accountId=" + accountId + "&orderId=" + data.get("orderId");
        if (data.get("xyGoodsId") != null) return "/goods?accountId=" + accountId + "&goodsId=" + data.get("xyGoodsId");
        if (data.get("conversationId") != null) return "/messages?accountId=" + accountId + "&conversationId=" + data.get("conversationId");
        if (accountId != null) return "/accounts?accountId=" + accountId;
        return "/operations-health";
    }

    private String severity(String type) {
        if (Set.of("DELIVERY_EXCEPTION", "ACCOUNT_VERIFICATION_REQUIRED", "PRODUCT_PUBLISH_FAILED", "PENALTY_CREATED").contains(type)) return "ERROR";
        if (Set.of("ACCOUNT_OFFLINE", "CREDENTIAL_EXPIRED", "KAMI_STOCK_LOW", "CONVERSATION_SLA_BREACHED", "REFUND_REQUESTED").contains(type)) return "WARNING";
        return "INFO";
    }

    private String scope(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1=0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return " AND (" + alias + ".xianyu_account_id IS NULL OR " + alias + ".xianyu_account_id IN (" + ids + "))";
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(400, "通知数据格式无效", e); }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception ignored) { return value; }
    }

    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String limit(String value, int max) { String normalized=trim(value); return normalized==null?null:normalized.substring(0, Math.min(max, normalized.length())); }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
}
