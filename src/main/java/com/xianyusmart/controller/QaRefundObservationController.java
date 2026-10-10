package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.AiHandoffService;
import com.xianyusmart.service.RefundObservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicitly enabled, credential-free QA only; observations use the real persistence service. */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/order-after-sales/b07")
public class QaRefundObservationController {
    private final JdbcTemplate jdbc;
    private final RefundObservationService refunds;
    private final AccountAccessService access;
    private final boolean enabled;
    private final AiHandoffService handoffs;

    public QaRefundObservationController(JdbcTemplate jdbc, RefundObservationService refunds,
                                         AccountAccessService access, AiHandoffService handoffs, @Value("${app.b07-qa.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.refunds = refunds; this.access = access; this.enabled = enabled;
        this.handoffs = handoffs;
    }

    private void requireQa() {
        if (!enabled) throw new BusinessException(404, "B07 隔离验收未启用");
        if (!Long.valueOf(1).equals(TenantContext.get())) throw new BusinessException(403, "仅允许隔离租户1");
    }

    private String orderId(String key) {
        requireQa();
        if (key == null || !key.matches("[A-Za-z0-9-]{1,40}")) throw new BusinessException(400, "夹具标识无效");
        return "QA-B07-" + key;
    }

    private Long account(String unb) {
        Long id = jdbc.queryForObject("SELECT id FROM xianyu_account WHERE tenant_id=1 AND unb=?", Long.class, unb);
        access.requireAccess(id);
        Long credentials = jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id=?", Long.class, id);
        if (credentials == null || credentials != 0) throw new BusinessException(403, "验收账号存在凭据，拒绝执行");
        return id;
    }

    @PostMapping("/fixtures")
    @Transactional
    public ResultObject<Map<String, Object>> fixture(@RequestBody Fixture command) {
        String orderId = orderId(command == null ? null : command.key());
        for (String participant : List.of("SELLER", "BUYER")) {
            jdbc.update("""
                INSERT INTO xianyu_account (tenant_id,unb,account_note,status)
                VALUES (1,?,?,0) ON DUPLICATE KEY UPDATE unb=VALUES(unb)
                """, "QA-B07-" + participant, "B07 隔离" + (participant.equals("SELLER") ? "卖家" : "买家"));
        }
        Long seller = account("QA-B07-SELLER"), buyer = account("QA-B07-BUYER");
        boolean delivered = Boolean.TRUE.equals(command.delivered());
        jdbc.update("""
            INSERT IGNORE INTO xianyu_goods_order
                (tenant_id,xianyu_account_id,xy_goods_id,pnm_id,order_id,buyer_user_id,buyer_user_name,sid,
                 goods_title,state,confirm_state,delivery_status,order_status_code,order_amount)
            VALUES (1,?,'QA-B07-GOODS',?,?, 'QA-B07-BUYER','隔离买家','QA-B07-SESSION',
                    'B07 隔离测试商品',?,?,?,'PAID',9.90)
            """, seller, orderId, orderId, delivered ? 1 : 0, delivered ? 1 : 0, delivered ? "COMPLETED" : "PENDING");
        return ResultObject.success(Map.of("platformNetworkCalls", false, "sellerAccountId", seller,
                "buyerAccountId", buyer, "orderId", orderId));
    }

    @PostMapping("/observe")
    public ResultObject<Map<String, Object>> observe(@RequestBody Observation command) {
        String orderId = orderId(command == null ? null : command.key());
        if (!Set.of("SELLER", "BUYER", "WRONG_SENDER", "UNKNOWN_ORDER").contains(command.participant()))
            throw new BusinessException(400, "参与方无效");
        Long id = account("QA-B07-" + (command.participant().equals("BUYER") ? "BUYER" : "SELLER"));
        String target = command.participant().equals("UNKNOWN_ORDER") ? orderId + "-missing" : orderId;
        String outcome;
        if (command.status() != null) {
            outcome = refunds.observeStatus(id, target, "QA-B07-BUYER", command.status());
        } else {
            ChatMessageData data = new ChatMessageData();
            data.setXianyuAccountId(id); data.setOrderId(target); data.setSId("QA-B07-SESSION");
            data.setSenderUserId(command.participant().equals("WRONG_SENDER") ? "QA-B07-OTHER" : "QA-B07-BUYER");
            data.setPnmId("QA-B07-CARD-" + command.key());
            outcome = refunds.observeMessage(data);
        }
        return ResultObject.success(Map.of("platformNetworkCalls", false, "outcome", outcome));
    }

    @GetMapping("/evidence/{key}")
    public ResultObject<Map<String, Object>> evidence(@PathVariable String key) {
        String orderId = orderId(key);
        Long seller = account("QA-B07-SELLER"), buyer = account("QA-B07-BUYER");
        return ResultObject.success(Map.of(
            "orders", jdbc.queryForList("SELECT id,xianyu_account_id,order_id,state,confirm_state,delivery_status,refund_status FROM xianyu_goods_order WHERE tenant_id=1 AND xianyu_account_id=? AND order_id=?", seller, orderId),
            "refunds", jdbc.queryForList("SELECT id,xianyu_account_id,platform_status,local_handling_status,requested_amount,refund_reason,decision_deadline FROM xianyu_refund_case WHERE tenant_id=1 AND order_id=? AND xianyu_account_id IN (?,?)", orderId, seller, buyer),
            "events", jdbc.queryForList("SELECT id,event_type,request_id,field_diff_json FROM xianyu_order_event WHERE tenant_id=1 AND order_id=? AND xianyu_account_id=?", orderId, seller),
            "audit", jdbc.queryForList("SELECT id,request_id,operation_type FROM xianyu_operation_log WHERE tenant_id=1 AND target_id=? AND xianyu_account_id=?", orderId, seller),
            "notifications", jdbc.queryForList("SELECT event_id,xianyu_account_id,handling_status,target_route FROM xianyu_notification_event WHERE tenant_id=1 AND business_object_id=? AND xianyu_account_id IN (?,?)", orderId, seller, buyer)));
    }

    @PostMapping("/message-handoff")
    public ResultObject<Map<String, Object>> messageHandoff(@RequestBody Handoff command) {
        String key = orderId(command == null ? null : command.key());
        if (!Set.of("MESSAGE", "SESSION").contains(command.scope())) throw new BusinessException(400, "作用范围无效");
        Long seller = account("QA-B07-SELLER");
        String reason = command.scope().equals("MESSAGE") ? "REFUND_QUESTION" : "BUYER_REQUESTED_HUMAN";
        String session = key + "-SESSION";
        Map<String, Object> task = handoffs.open(new AiHandoffService.OpenCommand(seller, session, "QA-B07-GOODS",
                "QA-B07-BUYER", null, reason, "隔离验收：仅当前消息退款待办或显式人工请求", null, null, 0L,
                key + "-handoff", key + "-handoff"));
        return ResultObject.success(Map.of("task", task, "blocksConversation", handoffs.hasPending(seller, session),
                "conversation", jdbc.queryForList("SELECT auto_reply_state,handoff_status,handoff_task_id FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?", seller, session)));
    }

    public record Fixture(String key, Boolean delivered) {}
    public record Observation(String key, String participant, String status) {}
    public record Handoff(String key, String scope) {}
}
