package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ORD-01~04：跨店订单、详情、物流事实和退款只读/决策边界。 */
@Service
public class OrderMatrixService {

    private static final Set<String> REFUND_FILTERS = Set.of(
            "ALL", "NONE", "REQUESTED", "PROCESSING", "APPROVED", "REJECTED", "CLOSED", "DISPUTE", "UNKNOWN");
    private static final Set<String> FLAGS = Set.of("NONE", "RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public OrderMatrixService(JdbcTemplate jdbcTemplate,
                              NamedParameterJdbcTemplate namedJdbc,
                              AccountAccessService accountAccessService,
                              OperationLogService operationLogService,
                              ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> list(OrderFilter input) {
        OrderFilter filter = normalize(input);
        QueryParts query = queryParts(filter);
        Integer total = namedJdbc.queryForObject("SELECT COUNT(*) " + query.fromWhere(), query.params(), Integer.class);
        int safeTotal = total == null ? 0 : total;
        int offset = Math.min((filter.page() - 1) * filter.pageSize(), safeTotal);
        MapSqlParameterSource pageParams = copy(query.params())
                .addValue("limit", filter.pageSize()).addValue("offset", offset);
        List<Map<String, Object>> records = namedJdbc.query("""
                SELECT orders.*, account.account_note, account.unb, goods.cover_pic
                """ + query.fromWhere() + " ORDER BY orders.create_time DESC, orders.id DESC LIMIT :limit OFFSET :offset",
                pageParams, (rs, rowNum) -> orderRow(rs));

        Map<String, Object> summary = namedJdbc.query("""
                SELECT COUNT(*) order_count,
                       SUM(order_amount) gmv,
                       SUM(order_amount IS NOT NULL) known_amount_count,
                       SUM(order_status_code='WAIT_BUYER_PAY') pending_payment_count,
                       SUM(delivery_status IN ('PENDING','PROCESSING','RETRY_WAIT')) pending_delivery_count,
                       SUM(refund_status IN ('REQUESTED','PROCESSING','DISPUTE')) refunding_count,
                       SUM(refund_amount) refund_amount,
                       SUM(refund_amount IS NOT NULL) known_refund_amount_count
                """ + query.fromWhere(), query.params(), rs -> {
            if (!rs.next()) return emptySummary();
            Map<String, Object> result = new LinkedHashMap<>();
            int count = rs.getInt("order_count");
            int knownAmounts = rs.getInt("known_amount_count");
            int knownRefunds = rs.getInt("known_refund_amount_count");
            BigDecimal gmv = rs.getBigDecimal("gmv");
            BigDecimal refunds = rs.getBigDecimal("refund_amount");
            result.put("orderCount", count);
            result.put("gmv", knownAmounts == 0 ? null : gmv);
            result.put("knownAmountCount", knownAmounts);
            result.put("pendingPaymentCount", rs.getLong("pending_payment_count"));
            result.put("pendingDeliveryCount", rs.getLong("pending_delivery_count"));
            result.put("refundingCount", rs.getLong("refunding_count"));
            result.put("refundAmount", knownRefunds == 0 ? null : refunds);
            result.put("knownRefundAmountCount", knownRefunds);
            result.put("refundRate", knownAmounts == 0 || knownRefunds == 0 || gmv == null || gmv.signum() == 0
                    ? null : refunds.divide(gmv, 4, RoundingMode.HALF_UP));
            result.put("coverageStatus", coverage(count, knownAmounts));
            return result;
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", records);
        response.put("summary", summary);
        response.put("summaryScope", "FILTERED_RESULT");
        response.put("total", safeTotal);
        response.put("page", filter.page());
        response.put("pageSize", filter.pageSize());
        response.put("totalPages", (int) Math.ceil((double) safeTotal / filter.pageSize()));
        response.put("dataset", datasetEvidence(filter.accountIds()));
        response.put("dataNotice", "订单来自本地事件/平台快照；金额或退款未采集时保持空值，不按0计入。订单创建不等同平台全量订单同步。");
        return response;
    }

    public Map<String, Object> detail(Long orderRecordId) {
        Map<String, Object> order = requireOrder(orderRecordId);
        Long accountId = number(order.get("accountId"));
        String orderId = text(order.get("orderId"));
        String sessionId = text(order.get("sessionId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("refunds", refunds(orderRecordId));
        result.put("messages", sessionId.isBlank() ? List.of() : jdbcTemplate.query("""
                SELECT pnm_id, content_type, msg_content, sender_user_name, sender_user_id, message_time, create_time
                  FROM xianyu_chat_message
                 WHERE tenant_id=? AND xianyu_account_id=? AND s_id=?
                 ORDER BY message_time DESC, id DESC LIMIT 100
                """, (rs, rowNum) -> {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", rs.getString("pnm_id"));
            message.put("contentType", nullableInt(rs, "content_type"));
            message.put("content", rs.getString("msg_content"));
            message.put("senderName", rs.getString("sender_user_name"));
            message.put("senderId", rs.getString("sender_user_id"));
            message.put("messageTime", nullableLong(rs, "message_time"));
            message.put("storedAt", instant(rs, "create_time"));
            return message;
        }, tenant(), accountId, sessionId));
        result.put("timeline", timeline(orderRecordId, orderId));
        result.put("navigationContext", Map.of(
                "returnToConversationSupported", !sessionId.isBlank(),
                "conversationId", sessionId,
                "accountId", accountId));
        result.put("capabilities", capabilities(accountId, order));
        return result;
    }

    public Map<String, Object> capabilities(Long accountId) {
        accountAccessService.requireAccess(accountId);
        return capabilities(accountId, null);
    }

    private Map<String,Object> capabilities(Long accountId, Map<String,Object> order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("virtualDelivery", Map.of("status", "READY", "source", "LOCAL_AUTOMATION"));
        result.put("physicalShipmentWrite", Map.of("status", "NOT_VERIFIED",
                "reason", "当前接入通道未验证实物运单写入接口；可记录已在平台确认的运单事实，不伪造平台发货成功"));
        result.put("logisticsTracking", Map.of("status", "PARTIAL", "source", "LOCAL_OR_IMPORTED_SNAPSHOT"));
        result.put("refundDecision", Map.of("status", "UNAVAILABLE",
                "reason", "当前没有可靠退款同意/拒绝平台接口，仅展示快照并允许补充内部说明"));
        result.put("dispute", Map.of("status", "READ_ONLY", "reason", "小法庭/争议仅展示平台同步状态"));
        result.put("orderNote", Map.of("status", "READY", "source", "LOCAL"));
        result.put("orderPriceChange", Map.of("status", "UNAVAILABLE",
                "reason", "当前接入通道未验证订单改价接口，不提供假按钮"));
        boolean completed = order != null && ("COMPLETED".equals(order.get("deliveryStatus"))
                || Boolean.TRUE.equals(order.get("buyerConfirmedReceipt")));
        result.put("receiptReminder", Map.of("status", order == null ? "REQUIRES_ORDER" : completed ? "NOT_APPLICABLE" : "LOCAL_AUTOMATION_ONLY",
                "reason", completed ? "买家已确认收货或订单已完成" : "仅按已配置的自动跟进规则执行，未验证平台原生提醒接口"));
        boolean rated = order != null && number(order.get("rateStatus")) == 1;
        result.put("rating", Map.of("status", order == null ? "REQUIRES_ORDER" : rated ? "COMPLETED" : completed ? "READY_AUTOMATION" : "WAITING_ORDER_STATE",
                "reason", rated ? "评价已记录" : completed ? "订单状态满足自动评价条件" : "需等待确认收货/交易完成"));
        return result;
    }

    @Transactional
    public Map<String, Object> recordPhysicalShipment(Long orderRecordId, ShipmentCommand command) {
        Map<String, Object> order = requireOrder(orderRecordId);
        requireRequest(command == null ? null : command.requestId());
        if (!Boolean.TRUE.equals(command.platformConfirmed())) {
            throw new BusinessException(409, "仅允许记录已在闲鱼平台确认的实物运单；本系统当前不会伪造平台发货成功");
        }
        String company = required(command.logisticsCompanyName(), "物流公司", 128);
        String tracking = required(command.trackingNumber(), "运单号", 128);
        String expected = "确认记录订单" + text(order.get("orderId")) + "的实物运单" + company + "/" + tracking
                + "；平台已由人工确认发货";
        if (!expected.equals(command.confirmationText())) {
            throw new BusinessException(400, "确认范围不匹配，请重新预检并使用最新确认文案");
        }
        List<Map<String, Object>> replay = jdbcTemplate.queryForList(
                "SELECT id FROM xianyu_order_event WHERE tenant_id=? AND event_type='PHYSICAL_SHIPMENT_RECORDED' AND request_id=?",
                tenant(), command.requestId());
        if (!replay.isEmpty()) return Map.of("idempotentReplay", true, "order", requireOrder(orderRecordId));
        Long accountId = number(order.get("accountId"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("shipmentType", "PHYSICAL");
        after.put("logisticsCompanyCode", blank(command.logisticsCompanyCode()));
        after.put("logisticsCompanyName", company);
        after.put("trackingNumber", tracking);
        after.put("logisticsStatus", "SHIPPED_PLATFORM_CONFIRMED");
        jdbcTemplate.update("""
                INSERT INTO xianyu_order_event
                (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_origin,outcome_state,
                 data_source,operator_user_id,operator_username,request_id,idempotency_key,before_json,after_json)
                VALUES (?,?,?,?,'PHYSICAL_SHIPMENT_RECORDED','USER','MANUAL_PLATFORM_CONFIRMED',
                        'MANUAL_PLATFORM_CONFIRMATION',?,?,?,?,?,?)
                """, tenant(), accountId, orderRecordId, order.get("orderId"), UserContext.getUserId(),
                UserContext.getUsername(), command.requestId(), command.requestId(), json(order), json(after));
        jdbcTemplate.update("""
                UPDATE xianyu_goods_order
                   SET shipment_type='PHYSICAL', logistics_company_code=?, logistics_company_name=?,
                       tracking_number=?, logistics_status='SHIPPED_PLATFORM_CONFIRMED', logistics_updated_time=NOW(3),
                       delivery_status='COMPLETED', confirm_state=1, data_source='MANUAL_PLATFORM_CONFIRMATION',
                       sync_status='SUCCEEDED', coverage_status='PARTIAL', last_synced_time=NOW(3),
                       last_sync_request_id=?
                 WHERE tenant_id=? AND id=?
                """, blank(command.logisticsCompanyCode()), company, tracking, command.requestId(), tenant(), orderRecordId);
        audit(accountId, "ORDER_PHYSICAL_SHIPMENT_RECORD", "记录平台已确认的实物运单", command.requestId(),
                "MANUAL_PLATFORM_CONFIRMED", order, after);
        return Map.of("idempotentReplay", false, "order", requireOrder(orderRecordId));
    }

    public Map<String, Object> physicalShipmentPreview(Long orderRecordId, ShipmentCommand command) {
        Map<String, Object> order = requireOrder(orderRecordId);
        String company = required(command == null ? null : command.logisticsCompanyName(), "物流公司", 128);
        String tracking = required(command.trackingNumber(), "运单号", 128);
        String confirmation = "确认记录订单" + text(order.get("orderId")) + "的实物运单" + company + "/" + tracking
                + "；平台已由人工确认发货";
        return Map.of("orderRecordId", orderRecordId, "orderId", text(order.get("orderId")),
                "buyer", text(order.get("buyerName")), "confirmationText", confirmation,
                "platformWrite", "NOT_PERFORMED", "requiredEvidence", "platformConfirmed=true");
    }

    @Transactional
    public Map<String, Object> updateNote(Long orderRecordId, OrderNoteCommand command) {
        Map<String, Object> order = requireOrder(orderRecordId);
        requireRequest(command == null ? null : command.requestId());
        String flag = command.flag() == null ? "NONE" : command.flag().trim().toUpperCase(Locale.ROOT);
        if (!FLAGS.contains(flag)) throw new BusinessException(400, "订单旗帜无效");
        String note = blank(command.note());
        if (note != null && note.length() > 1000) throw new BusinessException(400, "订单备注不能超过1000个字符");
        Long accountId = number(order.get("accountId"));
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_order_event
                (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_origin,outcome_state,
                 data_source,operator_user_id,operator_username,request_id,idempotency_key,before_json,after_json)
                VALUES (?,?,?,?,'NOTE_UPDATED','USER','LOCAL_SUCCESS','LOCAL',?,?,?,?,?,?)
                """, tenant(), accountId, orderRecordId, order.get("orderId"), UserContext.getUserId(),
                UserContext.getUsername(), command.requestId(), command.requestId(), json(order),
                json(Map.of("flag", flag, "note", note == null ? "" : note)));
        if (inserted == 0) return Map.of("idempotentReplay", true, "order", requireOrder(orderRecordId));
        jdbcTemplate.update("UPDATE xianyu_goods_order SET order_flag=?, order_note=? WHERE tenant_id=? AND id=?",
                "NONE".equals(flag) ? null : flag, note, tenant(), orderRecordId);
        audit(accountId, "ORDER_NOTE_UPDATE", "更新订单旗帜与备注", command.requestId(),
                "LOCAL_SUCCESS", order, Map.of("flag", flag, "note", note == null ? "" : note));
        return Map.of("idempotentReplay", false, "order", requireOrder(orderRecordId));
    }

    public Map<String, Object> refundActionPreview(Long refundCaseId, RefundActionCommand command) {
        Map<String, Object> refund = requireRefund(refundCaseId);
        String action = normalizeRefundAction(command == null ? null : command.action());
        boolean available = "ADD_NOTE".equals(action);
        String summary = actionLabel(action) + "：订单" + text(refund.get("orderId")) + "，买家"
                + text(refund.get("buyerName")) + "，申请金额"
                + (refund.get("requestedAmount") == null ? "未同步" : refund.get("requestedAmount"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundCase", refund);
        result.put("action", action);
        result.put("available", available);
        result.put("confirmationText", summary);
        result.put("unavailableReason", available ? null : "当前通道未接入可靠退款决策接口，不提供假执行按钮");
        return result;
    }

    @Transactional
    public Map<String, Object> executeRefundAction(Long refundCaseId, RefundActionCommand command) {
        Map<String, Object> preview = refundActionPreview(refundCaseId, command);
        String action = text(preview.get("action"));
        requireRequest(command.requestId());
        if (!Boolean.TRUE.equals(preview.get("available"))) {
            throw new BusinessException(409, text(preview.get("unavailableReason")));
        }
        if (!text(preview.get("confirmationText")).equals(command.confirmationText())) {
            throw new BusinessException(400, "确认范围不匹配，请重新预检");
        }
        String reason = required(command.reason(), "补充说明", 1000);
        Map<String, Object> refund = requireRefund(refundCaseId);
        Long accountId = number(refund.get("accountId"));
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_refund_action
                (tenant_id,refund_case_id,xianyu_account_id,action_type,outcome_state,reason,request_id,
                 idempotency_key,operator_user_id,operator_username)
                VALUES (?,?,?,'ADD_NOTE','LOCAL_SUCCESS',?,?,?,?,?)
                """, tenant(), refundCaseId, accountId, reason, command.requestId(), command.requestId(),
                UserContext.getUserId(), UserContext.getUsername());
        if (inserted == 0) return Map.of("idempotentReplay", true, "refundCase", requireRefund(refundCaseId));
        jdbcTemplate.update("UPDATE xianyu_refund_case SET last_request_id=? WHERE tenant_id=? AND id=?",
                command.requestId(), tenant(), refundCaseId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_order_event
                (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_origin,outcome_state,
                 data_source,operator_user_id,operator_username,request_id,idempotency_key,after_json)
                VALUES (?,?,?,?,'REFUND_NOTE_ADDED','USER','LOCAL_SUCCESS','LOCAL',?,?,?,?,?)
                """, tenant(), accountId, refund.get("orderRecordId"), refund.get("orderId"), UserContext.getUserId(),
                UserContext.getUsername(), command.requestId(), command.requestId(), json(Map.of("reason", reason)));
        audit(accountId, "REFUND_NOTE_ADD", "补充退款内部说明", command.requestId(),
                "LOCAL_SUCCESS", command, Map.of("refundCaseId", refundCaseId));
        return Map.of("idempotentReplay", false, "refundCase", requireRefund(refundCaseId));
    }

    private List<Map<String, Object>> refunds(Long orderRecordId) {
        return jdbcTemplate.query("""
                SELECT refunds.*, orders.buyer_user_name
                  FROM xianyu_refund_case refunds
                  JOIN xianyu_goods_order orders ON orders.id=refunds.order_record_id AND orders.tenant_id=refunds.tenant_id
                 WHERE refunds.tenant_id=? AND refunds.order_record_id=? ORDER BY refunds.updated_time DESC
                """, (rs, rowNum) -> refundRow(rs), tenant(), orderRecordId);
    }

    private List<Map<String, Object>> timeline(Long orderRecordId, String orderId) {
        List<Map<String, Object>> timeline = new ArrayList<>(jdbcTemplate.query("""
                SELECT event_type,outcome_state,data_source,operator_username,request_id,after_json,error_message,created_time
                  FROM xianyu_order_event WHERE tenant_id=? AND order_record_id=?
                 ORDER BY created_time DESC,id DESC LIMIT 200
                """, (rs, rowNum) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", rs.getString("event_type"));
            event.put("outcomeState", rs.getString("outcome_state"));
            event.put("dataSource", rs.getString("data_source"));
            event.put("operatorUsername", rs.getString("operator_username"));
            event.put("requestId", rs.getString("request_id"));
            event.put("detail", readJson(rs.getString("after_json")));
            event.put("error", rs.getString("error_message"));
            event.put("createdTime", instant(rs, "created_time"));
            return event;
        }, tenant(), orderRecordId));
        if (orderId != null && !orderId.isBlank()) {
            timeline.addAll(jdbcTemplate.query("""
                    SELECT operation_type,operation_desc,operation_status,outcome_state,data_source,
                           operator_username,request_id,error_message,create_time
                      FROM xianyu_operation_log
                     WHERE tenant_id=? AND target_id=? AND (target_type='ORDER' OR operation_module IN ('订单管理','自动发货'))
                     ORDER BY create_time DESC LIMIT 100
                    """, (rs, rowNum) -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", rs.getString("operation_type"));
                event.put("description", rs.getString("operation_desc"));
                event.put("operationStatus", nullableInt(rs, "operation_status"));
                event.put("outcomeState", rs.getString("outcome_state"));
                event.put("dataSource", rs.getString("data_source"));
                event.put("operatorUsername", rs.getString("operator_username"));
                event.put("requestId", rs.getString("request_id"));
                event.put("error", rs.getString("error_message"));
                event.put("createdTimeEpochMs", nullableLong(rs, "create_time"));
                return event;
            }, tenant(), orderId));
        }
        return timeline;
    }

    private Map<String, Object> requireOrder(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "订单记录ID无效");
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT orders.*, account.account_note, account.unb, goods.cover_pic
                  FROM xianyu_goods_order orders
                  JOIN xianyu_account account ON account.id=orders.xianyu_account_id AND account.tenant_id=orders.tenant_id
                  LEFT JOIN xianyu_goods goods ON goods.tenant_id=orders.tenant_id
                    AND goods.xianyu_account_id=orders.xianyu_account_id AND goods.xy_good_id=orders.xy_goods_id
                 WHERE orders.tenant_id=? AND orders.id=?
                """, (rs, rowNum) -> orderRow(rs), tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "订单不存在或不属于当前经营主体");
        Long accountId = number(rows.getFirst().get("accountId"));
        accountAccessService.requireAccess(accountId);
        return rows.getFirst();
    }

    private Map<String, Object> requireRefund(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "退款记录ID无效");
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT refunds.*, orders.buyer_user_name
                  FROM xianyu_refund_case refunds
                  JOIN xianyu_goods_order orders ON orders.id=refunds.order_record_id AND orders.tenant_id=refunds.tenant_id
                 WHERE refunds.tenant_id=? AND refunds.id=?
                """, (rs, rowNum) -> refundRow(rs), tenant(), id);
        if (rows.isEmpty()) throw new BusinessException(404, "退款记录不存在或不属于当前经营主体");
        accountAccessService.requireAccess(number(rows.getFirst().get("accountId")));
        return rows.getFirst();
    }

    private QueryParts queryParts(OrderFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenant());
        StringBuilder sql = new StringBuilder(" FROM xianyu_goods_order orders")
                .append(" JOIN xianyu_account account ON account.id=orders.xianyu_account_id AND account.tenant_id=orders.tenant_id")
                .append(" LEFT JOIN xianyu_goods goods ON goods.tenant_id=orders.tenant_id")
                .append(" AND goods.xianyu_account_id=orders.xianyu_account_id AND goods.xy_good_id=orders.xy_goods_id")
                .append(" WHERE orders.tenant_id=:tenantId");
        appendScope(sql, params, filter.accountIds());
        if (filter.search() != null) {
            sql.append(" AND (orders.order_id LIKE :search OR orders.buyer_user_id LIKE :search OR orders.buyer_user_name LIKE :search")
                    .append(" OR orders.xy_goods_id LIKE :search OR goods.title LIKE :search OR orders.sku_name LIKE :search)");
            params.addValue("search", "%" + filter.search() + "%");
        }
        if (filter.deliveryStatus() != null) {
            sql.append(" AND orders.delivery_status=:deliveryStatus");
            params.addValue("deliveryStatus", filter.deliveryStatus());
        }
        if (filter.orderStatus() != null) {
            sql.append(" AND orders.order_status_code=:orderStatus");
            params.addValue("orderStatus", filter.orderStatus());
        }
        if (!"ALL".equals(filter.refundStatus())) {
            sql.append(" AND orders.refund_status=:refundStatus");
            params.addValue("refundStatus", filter.refundStatus());
        }
        if (filter.startDate() != null) {
            sql.append(" AND orders.create_time>=:startTime");
            params.addValue("startTime", filter.startDate().atStartOfDay());
        }
        if (filter.endDate() != null) {
            sql.append(" AND orders.create_time<:endTime");
            params.addValue("endTime", filter.endDate().plusDays(1).atStartOfDay());
        }
        return new QueryParts(sql.toString(), params);
    }

    private void appendScope(StringBuilder sql, MapSqlParameterSource params, List<Long> requested) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        Set<Long> allowed = scope == null || scope.unrestricted() ? null : scope.accountIds();
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        if (requested != null) effective.addAll(requested);
        if (allowed != null) {
            if (effective.isEmpty()) effective.addAll(allowed);
            else effective.retainAll(allowed);
        }
        if ((allowed != null || requested != null && !requested.isEmpty()) && effective.isEmpty()) sql.append(" AND 1=0");
        else if (!effective.isEmpty()) {
            sql.append(" AND orders.xianyu_account_id IN (:accountIds)");
            params.addValue("accountIds", effective);
        }
    }

    private Map<String, Object> orderRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderRecordId", rs.getLong("id"));
        row.put("accountId", nullableLong(rs, "xianyu_account_id"));
        row.put("accountName", rs.getString("account_note"));
        row.put("accountUnb", rs.getString("unb"));
        row.put("orderId", rs.getString("order_id"));
        row.put("buyerId", rs.getString("buyer_user_id"));
        row.put("buyerName", rs.getString("buyer_user_name"));
        row.put("sessionId", rs.getString("sid"));
        row.put("goodsId", rs.getString("xy_goods_id"));
        row.put("goodsTitle", rs.getString("goods_title"));
        row.put("goodsCover", rs.getString("cover_pic"));
        row.put("skuId", rs.getString("sku_id"));
        row.put("skuName", rs.getString("sku_name"));
        row.put("quantity", nullableInt(rs, "buy_num"));
        row.put("amount", rs.getBigDecimal("order_amount"));
        row.put("currency", rs.getString("currency_code"));
        row.put("orderStatus", rs.getString("order_status_code"));
        row.put("platformTradeStatus", rs.getString("platform_trade_status"));
        row.put("deliveryStatus", rs.getString("delivery_status"));
        row.put("deliveryChannel", rs.getString("delivery_channel"));
        row.put("deliveredQuantity", nullableInt(rs, "delivered_quantity"));
        row.put("expectedQuantity", nullableInt(rs, "expected_quantity"));
        row.put("refundStatus", rs.getString("refund_status"));
        row.put("refundAmount", rs.getBigDecimal("refund_amount"));
        row.put("flag", rs.getString("order_flag"));
        row.put("note", rs.getString("order_note"));
        row.put("shipmentType", rs.getString("shipment_type"));
        row.put("logisticsCompanyCode", rs.getString("logistics_company_code"));
        row.put("logisticsCompanyName", rs.getString("logistics_company_name"));
        row.put("trackingNumber", rs.getString("tracking_number"));
        row.put("logisticsStatus", rs.getString("logistics_status"));
        row.put("logisticsLastEvent", rs.getString("logistics_last_event"));
        row.put("logisticsUpdatedTime", instant(rs, "logistics_updated_time"));
        row.put("orderCreateTimeRaw", rs.getString("order_create_time"));
        row.put("payTimeRaw", rs.getString("pay_success_time"));
        row.put("consignTimeRaw", rs.getString("consign_time"));
        row.put("rateStatus", nullableInt(rs, "rate_status"));
        row.put("rateTime", instant(rs, "rate_time"));
        row.put("lastErrorCode", rs.getString("last_error_code"));
        row.put("lastErrorMessage", rs.getString("last_error_message"));
        row.put("hasException", rs.getString("last_error_code") != null || "REVIEW_REQUIRED".equals(rs.getString("delivery_status")));
        row.put("dataSource", rs.getString("data_source"));
        row.put("syncStatus", rs.getString("sync_status"));
        row.put("coverageStatus", rs.getString("coverage_status"));
        row.put("lastSyncedTime", instant(rs, "last_synced_time"));
        row.put("lastSyncRequestId", rs.getString("last_sync_request_id"));
        row.put("createdTime", instant(rs, "create_time"));
        return row;
    }

    private Map<String, Object> refundRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("refundCaseId", rs.getLong("id"));
        row.put("accountId", nullableLong(rs, "xianyu_account_id"));
        row.put("orderRecordId", nullableLong(rs, "order_record_id"));
        row.put("orderId", rs.getString("order_id"));
        row.put("buyerName", rs.getString("buyer_user_name"));
        row.put("platformRefundId", rs.getString("platform_refund_id"));
        row.put("type", rs.getString("refund_type"));
        row.put("reason", rs.getString("refund_reason"));
        row.put("requestedAmount", rs.getBigDecimal("requested_amount"));
        row.put("approvedAmount", rs.getBigDecimal("approved_amount"));
        row.put("currency", rs.getString("currency_code"));
        row.put("evidence", readJson(rs.getString("evidence_json")));
        row.put("appliedTime", instant(rs, "applied_time"));
        row.put("decisionDeadline", instant(rs, "decision_deadline"));
        row.put("platformStatus", rs.getString("platform_status"));
        row.put("disputeStatus", rs.getString("dispute_status"));
        row.put("source", rs.getString("source"));
        row.put("syncStatus", rs.getString("sync_status"));
        row.put("coverageStatus", rs.getString("coverage_status"));
        row.put("syncedAt", instant(rs, "synced_at"));
        row.put("rawSnapshotHash", rs.getString("raw_snapshot_hash"));
        return row;
    }

    private Map<String, Object> datasetEvidence(List<Long> accountIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) account_count, SUM(state.id IS NOT NULL) state_count,
                       SUM(state.coverage_status='FULL') full_count, MAX(state.as_of_time) as_of_time,
                       MAX(state.last_success_time) last_success_time
                  FROM xianyu_account account
                  LEFT JOIN xianyu_account_dataset_state state ON state.tenant_id=account.tenant_id
                    AND state.xianyu_account_id=account.id AND state.dataset_code='ORDERS'
                 WHERE account.tenant_id=:tenantId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenant());
        appendAccountOnlyScope(sql, params, accountIds);
        return namedJdbc.query(sql.toString(), params, rs -> {
            rs.next();
            int accounts = rs.getInt("account_count");
            int states = rs.getInt("state_count");
            int full = rs.getInt("full_count");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "LOCAL_EVENT_OR_PLATFORM_SNAPSHOT");
            result.put("accountCount", accounts);
            result.put("coveredAccountCount", states);
            result.put("coverageStatus", accounts == 0 || states == 0 ? "UNSYNCED" : full == accounts ? "FULL" : "PARTIAL");
            result.put("asOfTime", instant(rs, "as_of_time"));
            result.put("lastSuccessTime", instant(rs, "last_success_time"));
            return result;
        });
    }

    private void appendAccountOnlyScope(StringBuilder sql, MapSqlParameterSource params, List<Long> requested) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        Set<Long> allowed = scope == null || scope.unrestricted() ? null : scope.accountIds();
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        if (requested != null) effective.addAll(requested);
        if (allowed != null) {
            if (effective.isEmpty()) effective.addAll(allowed); else effective.retainAll(allowed);
        }
        if ((allowed != null || requested != null && !requested.isEmpty()) && effective.isEmpty()) sql.append(" AND 1=0");
        else if (!effective.isEmpty()) {
            sql.append(" AND account.id IN (:datasetAccountIds)");
            params.addValue("datasetAccountIds", effective);
        }
    }

    private OrderFilter normalize(OrderFilter value) {
        OrderFilter input = value == null
                ? new OrderFilter(null, null, null, null, null, null, null, 1, 20) : value;
        List<Long> accounts = input.accountIds() == null ? List.of() : input.accountIds().stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        accounts.forEach(accountAccessService::requireAccess);
        String refund = input.refundStatus() == null ? "ALL" : upper(input.refundStatus());
        if (!REFUND_FILTERS.contains(refund)) throw new BusinessException(400, "退款状态无效");
        int page = input.page() == null || input.page() < 1 ? 1 : input.page();
        int size = input.pageSize() == null || input.pageSize() < 1 ? 20 : Math.min(100, input.pageSize());
        if (input.startDate() != null && input.endDate() != null && input.startDate().isAfter(input.endDate())) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        return new OrderFilter(blank(input.search()), accounts, upper(input.orderStatus()), upper(input.deliveryStatus()),
                refund, input.startDate(), input.endDate(), page, size);
    }

    private Map<String, Object> emptySummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderCount", 0);
        result.put("gmv", null);
        result.put("knownAmountCount", 0);
        result.put("pendingPaymentCount", 0);
        result.put("pendingDeliveryCount", 0);
        result.put("refundingCount", 0);
        result.put("refundAmount", null);
        result.put("knownRefundAmountCount", 0);
        result.put("refundRate", null);
        result.put("coverageStatus", "UNSYNCED");
        return result;
    }

    private String coverage(int total, int known) {
        return total == 0 || known == 0 ? "UNSYNCED" : known == total ? "FULL" : "PARTIAL";
    }

    private void audit(Long accountId, String type, String description, String requestId,
                       String outcome, Object request, Object response) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("订单与退款");
        log.setOperationDesc(description);
        log.setOperationStatus(1);
        log.setTargetType("ORDER");
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState(outcome);
        log.setDataSource(outcome.startsWith("MANUAL_PLATFORM") ? "MANUAL_PLATFORM_CONFIRMATION" : "LOCAL");
        log.setRequestParams(json(request));
        log.setResponseResult(json(response));
        operationLogService.log(log);
    }

    private String normalizeRefundAction(String value) {
        String action = upper(value);
        if (!Set.of("APPROVE", "REJECT", "ADD_NOTE").contains(action)) throw new BusinessException(400, "退款动作无效");
        return action;
    }

    private String actionLabel(String action) {
        return switch (action) { case "APPROVE" -> "同意退款"; case "REJECT" -> "拒绝退款"; default -> "补充退款说明"; };
    }

    private String requireRequest(String value) {
        return required(value, "requestId", 80);
    }

    private String required(String value, String label, int max) {
        String normalized = blank(value);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (normalized.length() > max) throw new BusinessException(400, label + "不能超过" + max + "个字符");
        return normalized;
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException(400, "JSON序列化失败", e); }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readValue(value, Object.class); }
        catch (JsonProcessingException ignored) { return value; }
    }

    private MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        source.getValues().forEach(copy::addValue);
        return copy;
    }

    private static String blank(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String upper(String value) {
        String text = blank(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(text(value)); }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException { int value = rs.getInt(column); return rs.wasNull() ? null : value; }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }

    private record QueryParts(String fromWhere, MapSqlParameterSource params) {}

    public record OrderFilter(String search, List<Long> accountIds, String orderStatus, String deliveryStatus,
                              String refundStatus, LocalDate startDate, LocalDate endDate,
                              Integer page, Integer pageSize) {}

    public record ShipmentCommand(String requestId, String logisticsCompanyCode, String logisticsCompanyName,
                                  String trackingNumber, Boolean platformConfirmed, String confirmationText) {}

    public record OrderNoteCommand(String requestId, String flag, String note) {}

    public record RefundActionCommand(String requestId, String action, String reason, String confirmationText) {}
}
