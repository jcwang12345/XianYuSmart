package com.xianyusmart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 确认收货后邀评消息的持久化幂等、未知保护和账号日频控。 */
@Service
public class OrderEngagementService {
    public enum Decision { SEND, ALREADY_CONFIRMED, RESULT_UNKNOWN, RETRY_LATER, DAILY_LIMIT }
    public record Admission(Decision decision, Long eventId, String requestId, int attemptCount) {
        public boolean allowed() { return decision == Decision.SEND; }
    }

    private final JdbcTemplate jdbcTemplate;
    private final int dailyLimit;
    private final int maxAttempts;

    public OrderEngagementService(JdbcTemplate jdbcTemplate,
                                  @Value("${app.automation.engagement-daily-limit:20}") int dailyLimit,
                                  @Value("${app.automation.engagement-max-attempts:5}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.dailyLimit = Math.max(1, Math.min(dailyLimit, 200));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 20));
    }

    @Transactional
    public Admission beginInvite(Long tenantId, Long accountId, Long orderRecordId, String orderId,
                                 int messageIndex, String content) {
        if (tenantId == null || accountId == null || orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("邀评事件缺少租户、账号或订单");
        }
        // 账号行锁把日配额判断与事件创建串行化，多实例也不会突破账号上限。
        jdbcTemplate.queryForObject("SELECT id FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE",
                Long.class, tenantId, accountId);
        String eventKey = "REVIEW_INVITE:" + accountId + ":" + orderId + ":" + messageIndex;
        String requestId = "invite-" + tenantId + "-" + accountId + "-" + orderId + "-" + messageIndex;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,status,attempt_count,next_retry_time FROM xianyu_order_engagement_event
                 WHERE tenant_id=? AND event_key=? FOR UPDATE
                """, tenantId, eventKey);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.getFirst();
            Long eventId = ((Number) row.get("id")).longValue();
            String status = String.valueOf(row.get("status"));
            int attempts = ((Number) row.get("attempt_count")).intValue();
            if ("SUCCESS".equals(status)) return new Admission(Decision.ALREADY_CONFIRMED, eventId, requestId, attempts);
            if ("UNKNOWN".equals(status) || "PROCESSING".equals(status) || "MANUAL_REQUIRED".equals(status)) {
                return new Admission(Decision.RESULT_UNKNOWN, eventId, requestId, attempts);
            }
            if (attempts >= maxAttempts) return new Admission(Decision.RESULT_UNKNOWN, eventId, requestId, attempts);
            if (row.get("next_retry_time") instanceof java.sql.Timestamp retry
                    && retry.toLocalDateTime().isAfter(LocalDateTime.now())) {
                return new Admission(Decision.RETRY_LATER, eventId, requestId, attempts);
            }
            int claimed = jdbcTemplate.update("""
                    UPDATE xianyu_order_engagement_event
                       SET status='PROCESSING',attempt_count=attempt_count+1,error_message=NULL,next_retry_time=NULL
                     WHERE tenant_id=? AND id=? AND status='FAILED'
                    """, tenantId, eventId);
            return claimed == 1
                    ? new Admission(Decision.SEND, eventId, requestId, attempts + 1)
                    : new Admission(Decision.RESULT_UNKNOWN, eventId, requestId, attempts);
        }
        Integer used = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_order_engagement_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND event_type='REVIEW_INVITE'
                   AND status IN ('PROCESSING','SUCCESS','UNKNOWN','MANUAL_REQUIRED')
                   AND created_time>=CURRENT_DATE AND created_time<DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY)
                """, Integer.class, tenantId, accountId);
        if (used != null && used >= dailyLimit) {
            return new Admission(Decision.DAILY_LIMIT, null, requestId, 0);
        }
        jdbcTemplate.update("""
                INSERT INTO xianyu_order_engagement_event
                (tenant_id,xianyu_account_id,order_record_id,order_id,event_type,event_key,request_id,
                 status,attempt_count,content_sha256)
                VALUES (?,?,?,?, 'REVIEW_INVITE',?,?, 'PROCESSING',1,?)
                """, tenantId, accountId, orderRecordId, orderId, eventKey, requestId, sha256(content));
        Long eventId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_order_engagement_event WHERE tenant_id=? AND event_key=?
                """, Long.class, tenantId, eventKey);
        return new Admission(Decision.SEND, eventId, requestId, 1);
    }

    public void success(Long tenantId, Long eventId) {
        jdbcTemplate.update("""
                UPDATE xianyu_order_engagement_event
                   SET status='SUCCESS',sent_time=NOW(3),error_message=NULL,next_retry_time=NULL
                 WHERE tenant_id=? AND id=? AND status='PROCESSING'
                """, tenantId, eventId);
    }

    public boolean knownFailure(Long tenantId, Long eventId, String error, int attemptCount) {
        boolean manual = attemptCount >= maxAttempts;
        jdbcTemplate.update("""
                UPDATE xianyu_order_engagement_event
                   SET status=?,error_message=?,next_retry_time=?
                 WHERE tenant_id=? AND id=? AND status='PROCESSING'
                """, manual ? "MANUAL_REQUIRED" : "FAILED", trim(error),
                manual ? null : java.sql.Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)),
                tenantId, eventId);
        return manual;
    }

    public void unknown(Long tenantId, Long eventId, String error) {
        jdbcTemplate.update("""
                UPDATE xianyu_order_engagement_event
                   SET status='UNKNOWN',error_message=?,next_retry_time=NULL
                 WHERE tenant_id=? AND id=? AND status='PROCESSING'
                """, trim(error), tenantId, eventId);
    }

    public int dailyLimit() { return dailyLimit; }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成邀评内容指纹", e);
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
