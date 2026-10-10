package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Only verified seller-side observations may mutate an order or create a merchant refund case. */
@Service
public class RefundObservationService {
    private final JdbcTemplate jdbc;
    private final XianyuAccountMapper accounts;
    private final XianyuGoodsOrderMapper orders;
    private final KamiConfigService kami;
    private final NotificationCenterService notifications;
    private final ObjectMapper json;

    public RefundObservationService(JdbcTemplate jdbc, XianyuAccountMapper accounts,
                                    XianyuGoodsOrderMapper orders, KamiConfigService kami,
                                    NotificationCenterService notifications, ObjectMapper json) {
        this.jdbc = jdbc; this.accounts = accounts; this.orders = orders;
        this.kami = kami; this.notifications = notifications; this.json = json;
    }

    @Transactional
    public String observeMessage(ChatMessageData message) {
        if (message == null || message.getXianyuAccountId() == null) return "IGNORED";
        XianyuAccount account = accounts.selectById(message.getXianyuAccountId());
        if (account == null) return "IGNORED";
        // The sender of this platform card is the person requesting the refund.
        // Both participants receive it; an outgoing card is not a seller action item.
        if (hasText(account.getUnb()) && account.getUnb().equals(message.getSenderUserId())) return "BUYER_EVENT_IGNORED";
        Map<String, Object> order = sellerOrder(account, message.getOrderId(), message.getSenderUserId());
        if (order == null) {
            String identity = hasText(message.getPnmId()) ? message.getPnmId()
                    : String.valueOf(message.getSId()) + ":" + String.valueOf(message.getOrderId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dedupeKey", "refund-unverified:" + account.getId() + ":" + hash(identity).substring(0, 32));
            data.put("conversationId", Objects.toString(message.getSId(), ""));
            data.put("verificationRequired", true);
            notifications.dispatch("REFUND_REQUESTED", account.getId(), "退款相关消息待核实",
                    "收到平台退款系统消息，但订单号或卖家归属尚未核实。请在闲鱼官方订单售后核对；当前未更改订单及自动发货状态。", data);
            return "UNVERIFIED";
        }
        persist(account, order, "REQUESTED", "PLATFORM_EVENT", message.getPnmId());
        return "RECORDED";
    }

    @Transactional
    public String observeStatus(Long accountId, String orderId, String buyerId, String status) {
        if (!List.of("REFUNDING", "REFUNDED").contains(Objects.toString(status, ""))) return "IGNORED";
        XianyuAccount account = accounts.selectById(accountId);
        if (account == null || hasText(buyerId) && buyerId.equals(account.getUnb())) return "BUYER_EVENT_IGNORED";
        Map<String, Object> order = sellerOrder(account, orderId, buyerId);
        if (order == null) return "UNVERIFIED";
        persist(account, order, "REFUNDED".equals(status) ? "REFUNDED" : "PROCESSING", "PLATFORM_API", null);
        return "RECORDED";
    }

    private Map<String, Object> sellerOrder(XianyuAccount account, String orderId, String requestingUserId) {
        if (!hasText(orderId) || !hasText(account.getUnb()) || account.getTenantId() == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,order_id,xy_goods_id,buyer_user_id,refund_status,delivery_status
                  FROM xianyu_goods_order WHERE tenant_id=? AND xianyu_account_id=? AND order_id=? FOR UPDATE
                """, account.getTenantId(), account.getId(), orderId);
        if (rows.isEmpty()) return null;
        Map<String, Object> order = rows.getFirst();
        String buyer = Objects.toString(order.get("buyer_user_id"), "");
        if (!hasText(buyer) || buyer.equals(account.getUnb())) return null;
        if (hasText(requestingUserId) && !buyer.equals(requestingUserId)) return null;
        return order;
    }

    private void persist(XianyuAccount account, Map<String, Object> order, String observedStatus,
                         String source, String messageId) {
        Long tenant = account.getTenantId(), accountId = account.getId();
        String orderId = String.valueOf(order.get("order_id"));
        String oldStatus = Objects.toString(order.get("refund_status"), "NONE");
        // A delayed/replayed request cannot reopen an already resolved refund.
        String status = List.of("REFUNDED", "REJECTED", "CANCELLED").contains(oldStatus)
                ? oldStatus : "REQUESTED".equals(observedStatus) && "PROCESSING".equals(oldStatus) ? oldStatus : observedStatus;
        boolean stopped = orders.skipPendingTaskForRefund(accountId, orderId, "平台退款事件已确认，停止未完成的自动发货") == 1;
        if (stopped) kami.releaseReservation(orderId, accountId);
        jdbc.update("UPDATE xianyu_goods_order SET refund_status=? WHERE tenant_id=? AND xianyu_account_id=? AND id=?",
                status, tenant, accountId, order.get("id"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", source); evidence.put("orderId", orderId); evidence.put("observedStatus", observedStatus);
        if (hasText(messageId)) evidence.put("messageId", messageId);
        String snapshot = serialize(evidence);
        String requestId = "refund-observed-" + hash(accountId + ":" + orderId + ":" + status).substring(0, 40);
        jdbc.update("""
                INSERT INTO xianyu_refund_case
                    (tenant_id,xianyu_account_id,order_record_id,order_id,observation_key,platform_status,
                     source,sync_status,coverage_status,evidence_json,synced_at,raw_snapshot_hash,last_request_id)
                VALUES (?,?,?,?,?, ?,?,'PARTIAL','PARTIAL',?,NOW(3),?,?)
                ON DUPLICATE KEY UPDATE platform_status=VALUES(platform_status),source=VALUES(source),
                    evidence_json=VALUES(evidence_json),synced_at=NOW(3),raw_snapshot_hash=VALUES(raw_snapshot_hash),
                    last_request_id=VALUES(last_request_id)
                """, tenant, accountId, order.get("id"), orderId, "ORDER:" + orderId, status,
                source, snapshot, hash(snapshot), requestId);
        String diff = serialize(Map.of("refundStatus", Map.of("before", oldStatus, "after", status)));
        jdbc.update("""
                INSERT IGNORE INTO xianyu_order_event
                    (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_origin,outcome_state,
                     data_source,request_id,idempotency_key,before_json,after_json,field_diff_json)
                VALUES (?,?,?,?,'REFUND_OBSERVED','SYSTEM','PARTIAL',?,?,?,?,?,?)
                """, tenant, accountId, order.get("id"), orderId, source, requestId, requestId,
                serialize(Map.of("refundStatus", oldStatus)), snapshot, diff);
        jdbc.update("""
                INSERT INTO xianyu_operation_log
                    (tenant_id,xianyu_account_id,operator_username,operation_type,operation_module,operation_desc,
                     operation_status,outcome_state,data_source,target_type,target_id,request_id,idempotency_key,
                     request_params,response_result,field_diff_json,create_time)
                SELECT ?,?,'SYSTEM','REFUND_OBSERVED','订单售后','核实卖家退款事件',1,'PARTIAL',?,
                    'ORDER',?,?,?,?,?,?,UNIX_TIMESTAMP()*1000
                WHERE NOT EXISTS (SELECT 1 FROM xianyu_operation_log WHERE tenant_id=? AND request_id=? AND operation_type='REFUND_OBSERVED')
                """, tenant, accountId, source, orderId, requestId, requestId, snapshot,
                serialize(Map.of("refundStatus", status, "pendingDeliveryStopped", stopped)), diff, tenant, requestId);
        if (!List.of("REQUESTED", "PROCESSING").contains(status)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dedupeKey", "refund-requested:" + accountId + ":" + orderId);
        data.put("orderId", orderId); data.put("xyGoodsId", Objects.toString(order.get("xy_goods_id"), ""));
        data.put("refundStatus", status); data.put("verificationRequired", true);
        notifications.dispatch("REFUND_REQUESTED", accountId, "新退款申请待处理",
                "订单 " + orderId + " 的卖家归属和退款状态已核实。退款原因、金额和处理截止时间尚未同步，请在闲鱼官方订单售后核对。"
                        + (stopped ? " 未完成的自动发货已停止。" : " 交付状态请人工核对，系统不会自动退款或重复发货。"), data);
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("退款证据序列化失败", e); }
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
