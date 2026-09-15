package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Persists a terminal, non-deliverable notification chain for independent QA.
 * The outbox starts FAILED and its channel is disabled, so the dispatcher can
 * never claim it and no HTTP request is attempted.
 */
@RestController
@RequestMapping("/api/qa/notification-trace")
@ConditionalOnProperty(name = "app.product-batch.qa-mock.enabled", havingValue = "true")
public class QaNotificationTraceController {

    private final JdbcTemplate jdbcTemplate;
    private final long allowedTenantId;

    public QaNotificationTraceController(JdbcTemplate jdbcTemplate,
                                         @Value("${app.product-batch.qa-mock.tenant-id:-1}") long allowedTenantId) {
        this.jdbcTemplate = jdbcTemplate;
        this.allowedTenantId = allowedTenantId;
    }

    @PostMapping("/fixture")
    public ResultObject<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId != allowedTenantId) {
            throw new BusinessException(404, "隔离通知追踪夹具未对当前经营主体开放");
        }
        String requestId = text(request == null ? null : request.get("requestId"));
        if (requestId == null || requestId.length() > 80) throw new BusinessException(400, "requestId无效");
        String digest = sha256(requestId);
        String proposedEventId = "qa-trace-" + digest.substring(0, 32);
        String dedupeKey = "qa-notification-trace:" + digest;

        jdbcTemplate.update("""
                INSERT INTO xianyu_notification_channel
                    (tenant_id,channel_name,channel_type,webhook_url,event_types,scope_type,enabled,last_error_message)
                SELECT ?, 'QA 本地不外发通道', 'WEBHOOK', 'https://qa.invalid/no-send',
                       'PRODUCT_BATCH_FAILED', 'ALL', 0, 'QA fixture: network disabled'
                 WHERE NOT EXISTS (
                       SELECT 1 FROM xianyu_notification_channel
                        WHERE tenant_id=? AND channel_name='QA 本地不外发通道')
                """, tenantId, tenantId);
        Long channelId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_notification_channel
                 WHERE tenant_id=? AND channel_name='QA 本地不外发通道' LIMIT 1
                """, Long.class, tenantId);
        if (channelId == null) throw new BusinessException(500, "隔离通知通道创建失败");

        jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_notification_event
                    (tenant_id,event_id,event_type,business_object_type,business_object_id,severity,title,
                     content_summary,source,dedupe_key,target_route,data_json,handling_status)
                VALUES (?,?,'PRODUCT_BATCH_FAILED','QA_NOTIFICATION_TRACE',?,'WARNING',
                        'QA 通知追踪夹具','仅验证持久化关联；不会外发','QA_FIXTURE',?,
                        '/operations-health','{\"externalNetworkCalls\":0}','UNHANDLED')
                """, tenantId, proposedEventId, requestId, dedupeKey);
        String eventId = jdbcTemplate.queryForObject("""
                SELECT event_id FROM xianyu_notification_event
                 WHERE tenant_id=? AND event_type='PRODUCT_BATCH_FAILED' AND dedupe_key=?
                """, String.class, tenantId, dedupeKey);

        jdbcTemplate.update("""
                INSERT IGNORE INTO xianyu_notification_outbox
                    (tenant_id,channel_id,event_type,dedupe_key,event_id,title,content,data_json,status,
                     attempt_count,next_retry_time,last_error_message)
                VALUES (?,?,'PRODUCT_BATCH_FAILED',?,?,'QA 通知追踪夹具',
                        '仅验证持久化关联；不会外发','{\"externalNetworkCalls\":0}',
                        'FAILED',0,NOW(3),'QA fixture: deliberately not sent')
                """, tenantId, channelId, dedupeKey, eventId);
        Long outboxId = jdbcTemplate.queryForObject("""
                SELECT id FROM xianyu_notification_outbox
                 WHERE tenant_id=? AND channel_id=? AND event_type='PRODUCT_BATCH_FAILED' AND dedupe_key=?
                """, Long.class, tenantId, channelId, dedupeKey);
        if (outboxId == null) throw new BusinessException(500, "隔离通知发件箱创建失败");

        Integer logCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_notification_log
                 WHERE tenant_id=? AND event_id=? AND outbox_id=?
                """, Integer.class, tenantId, eventId, outboxId);
        if (logCount == null || logCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO xianyu_notification_log
                        (tenant_id,channel_id,event_id,outbox_id,event_type,title,send_status,delivery_status,error_message)
                    VALUES (?,?,?,?, 'PRODUCT_BATCH_FAILED','QA 通知追踪夹具',0,'NOT_SENT_QA',
                            'QA fixture: no external network attempted')
                    """, tenantId, channelId, eventId, outboxId);
        }

        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM xianyu_notification_event WHERE tenant_id=? AND event_id=?) inboxCount,
                  (SELECT COUNT(*) FROM xianyu_notification_outbox WHERE tenant_id=? AND event_id=?) outboxCount,
                  (SELECT COUNT(*) FROM xianyu_notification_log WHERE tenant_id=? AND event_id=?) logCount
                """, tenantId, eventId, tenantId, eventId, tenantId, eventId);
        return ResultObject.success(Map.of(
                "requestId", requestId,
                "eventId", eventId,
                "outboxId", outboxId,
                "channelId", channelId,
                "outboxStatus", "FAILED",
                "deliveryStatus", "NOT_SENT_QA",
                "externalNetworkCalls", 0,
                "counts", counts));
    }

    private String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
