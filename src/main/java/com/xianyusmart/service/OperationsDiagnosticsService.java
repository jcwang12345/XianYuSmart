package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.service.diagnostics.OperationsHealthEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * 运营诊断服务
 */
@Service
public class OperationsDiagnosticsService {

    private final JdbcTemplate jdbcTemplate;
    private final WebSocketService webSocketService;
    private final SystemUpdateService systemUpdateService;

    public OperationsDiagnosticsService(JdbcTemplate jdbcTemplate,
                                        WebSocketService webSocketService,
                                        SystemUpdateService systemUpdateService) {
        this.jdbcTemplate = jdbcTemplate;
        this.webSocketService = webSocketService;
        this.systemUpdateService = systemUpdateService;
    }

    public Map<String, Object> overview(boolean includePlatformChecks) {
        Long tenantId = requireTenantId();
        String evidenceTime = Instant.now().toString();
        long accountAbnormal = count("""
                SELECT COUNT(*)
                FROM xianyu_account
                WHERE tenant_id = ? AND status <> 1
                """, tenantId);
        long cookieInvalid = count("""
                SELECT COUNT(*)
                FROM xianyu_cookie cookie
                JOIN xianyu_account account ON account.id = cookie.xianyu_account_id
                WHERE account.tenant_id = ? AND cookie.cookie_status <> 1
                """, tenantId);
        long deliveryFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_order source
                WHERE source.tenant_id = ? AND source.delivery_status = 'FAILED'
                  AND NOT EXISTS (
                    SELECT 1 FROM xianyu_exception_acknowledgement ack
                    WHERE ack.tenant_id = source.tenant_id
                      AND ack.exception_type = 'DELIVERY'
                      AND ack.source_id = source.id
                      AND ack.source_version = source.exception_revision
                  )
                """, tenantId);
        long deliveryReview = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_order source
                WHERE source.tenant_id = ? AND source.delivery_status = 'REVIEW_REQUIRED'
                  AND NOT EXISTS (
                    SELECT 1 FROM xianyu_exception_acknowledgement ack
                    WHERE ack.tenant_id = source.tenant_id
                      AND ack.exception_type = 'DELIVERY'
                      AND ack.source_id = source.id
                      AND ack.source_version = source.exception_revision
                  )
                """, tenantId);
        long replyFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_auto_reply_record source
                WHERE source.tenant_id = ? AND source.state IN (-1,3)
                  AND NOT EXISTS (
                    SELECT 1 FROM xianyu_exception_acknowledgement ack
                    WHERE ack.tenant_id = source.tenant_id
                      AND ack.exception_type = 'AUTO_REPLY'
                      AND ack.source_id = source.id
                      AND ack.source_version = source.exception_revision
                  )
                """, tenantId);
        long lowStock = count("""
                SELECT COUNT(*)
                FROM xianyu_kami_config config
                WHERE config.tenant_id = ?
                  AND config.source_type = 'LOCAL'
                  AND config.alert_enabled = 1
                  AND (
                    (config.alert_threshold_type = 1
                      AND (SELECT COUNT(*) FROM xianyu_kami_item item
                           WHERE item.kami_config_id = config.id AND item.status = 0) < config.alert_threshold_value)
                    OR
                    (config.alert_threshold_type = 2 AND config.total_count > 0
                      AND ((SELECT COUNT(*) FROM xianyu_kami_item item
                            WHERE item.kami_config_id = config.id AND item.status = 0)
                           * 100 / config.total_count) < config.alert_threshold_value)
                  )
                """, tenantId);
        long externalReview = count("""
                SELECT COUNT(*)
                FROM xianyu_kami_external_request source
                WHERE source.tenant_id = ? AND (
                  source.request_status IN ('FAILED', 'REVIEW_REQUIRED')
                  OR (source.request_status = 'PROCESSING' AND source.update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
                )
                  AND NOT EXISTS (
                    SELECT 1 FROM xianyu_exception_acknowledgement ack
                    WHERE ack.tenant_id = source.tenant_id
                      AND ack.exception_type = 'EXTERNAL_SUPPLY'
                      AND ack.source_id = source.id
                      AND ack.source_version = source.exception_revision
                  )
                """, tenantId);
        long notificationFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_notification_log
                WHERE tenant_id = ? AND send_status = 0
                  AND create_time >= DATE_SUB(NOW(3), INTERVAL 1 DAY)
                """, tenantId);
        List<Long> activeAccountIds = jdbcTemplate.queryForList(
                "SELECT id FROM xianyu_account WHERE tenant_id = ? AND status = 1",
                Long.class, tenantId);
        long websocketDisconnected = activeAccountIds.stream()
                .filter(accountId -> !webSocketService.isConnected(accountId)).count();
        long versionWarning = 0;
        long updateAgentWarning = 0;
        long unknownCount = 0;
        String versionAction = "当前已是最新版本";
        String versionStatus = "HEALTHY";
        boolean updateAgentAvailable = true;
        String updateAgentStatus = "HEALTHY";
        if (includePlatformChecks) {
            try {
                var version = systemUpdateService.checkUpdate();
                if (Boolean.TRUE.equals(version.getHasUpdate())) {
                    versionWarning = 1;
                    versionStatus = "WARNING";
                    versionAction = "发现新版本 " + version.getLatestVersion() + "，可在右上角自动更新";
                }
            } catch (Exception e) {
                unknownCount++;
                versionStatus = "UNKNOWN";
                versionAction = "版本服务暂时不可用，当前版本状态未知，请稍后重试";
            }
            try {
                updateAgentAvailable = Boolean.TRUE.equals(systemUpdateService.updateAgentStatus().get("available"));
                updateAgentWarning = updateAgentAvailable ? 0 : 1;
                updateAgentStatus = updateAgentAvailable ? "HEALTHY" : "WARNING";
            } catch (Exception e) {
                updateAgentAvailable = false;
                updateAgentStatus = "UNKNOWN";
                unknownCount++;
            }
        }

        List<Map<String, Object>> checks = new ArrayList<>(List.of(
                check("DATABASE", "数据库服务", 0, "检查数据库连接", "数据库不可用会阻断全部业务读写", "DATABASE_SNAPSHOT", evidenceTime),
                check("WEBSOCKET", "实时连接", websocketDisconnected, "系统正在自动恢复断开的账号连接", "断线账号无法实时接收买家消息和订单事件", "LOCAL_RUNTIME", evidenceTime),
                check("ACCOUNT", "账号连接", accountAbnormal + cookieInvalid, "检查异常账号或更新登录凭证", "异常凭证会影响该店铺的同步、回复与履约", "DATABASE_SNAPSHOT", evidenceTime),
                check("DELIVERY", "自动发货", deliveryFailed + deliveryReview, "处理失败订单和待人工核对订单", "未处理订单可能延迟履约；结果未知时禁止重复发货", "DATABASE_SNAPSHOT", evidenceTime),
                check("REPLY", "自动回复", replyFailed, "检查失败回复记录", "回复失败可能导致买家等待或转人工", "DATABASE_SNAPSHOT", evidenceTime),
                check("STOCK", "卡密库存", lowStock, "补充低库存卡密仓库", "低库存可能导致后续虚拟商品无法自动履约", "DATABASE_SNAPSHOT", evidenceTime),
                check("EXTERNAL_SUPPLY", "外部卡密供货", externalReview, "核对失败或不确定的外部供货请求", "外部结果未知时必须人工核对，不能重复请求", "DATABASE_SNAPSHOT", evidenceTime),
                check("NOTIFICATION", "通知渠道", notificationFailed, "检查近24小时发送失败的通知", "业务事件仍会保留，但外部协作工具可能没有收到提醒", "DATABASE_SNAPSHOT", evidenceTime)
        ));
        if (includePlatformChecks) {
            checks.add(check("VERSION", "版本状态", versionWarning, versionStatus, versionAction,
                    "版本未知不等于已是最新版本", "UPDATE_SERVICE", evidenceTime));
            checks.add(check("UPDATE_AGENT", "自动更新服务", updateAgentWarning, updateAgentStatus,
                    updateAgentAvailable ? "自动更新服务运行正常" : "自动更新服务尚未就绪或状态未知",
                    "更新代理不可用时仍可运行当前版本，但不能自动升级", "LOCAL_RUNTIME", evidenceTime));
        }
        long criticalCount = deliveryFailed + deliveryReview + externalReview;
        long warningCount = accountAbnormal + cookieInvalid + websocketDisconnected
                + replyFailed + lowStock + notificationFailed + versionWarning + updateAgentWarning;

        Map<String, Object> result = new LinkedHashMap<>();
        long systemReminders = accountAbnormal + cookieInvalid + websocketDisconnected + lowStock
                + versionWarning + updateAgentWarning;
        long businessActions = deliveryFailed + deliveryReview + replyFailed + externalReview;
        long externalDeliveryFailures = notificationFailed;
        result.put("overallStatus", OperationsHealthEvaluator.overallStatus(criticalCount, warningCount, unknownCount));
        result.put("criticalCount", criticalCount);
        result.put("warningCount", warningCount);
        result.put("unknownCount", unknownCount);
        result.put("evidenceTime", evidenceTime);
        result.put("dataSource", "TENANT_SCOPED_DATABASE_AND_LOCAL_RUNTIME");
        result.put("countGroups", Map.of(
                "systemReminders", systemReminders,
                "businessActions", businessActions,
                "externalDeliveryFailures", externalDeliveryFailures));
        result.put("countRelationship", "系统提醒表示服务或配置状态；业务待办表示需要处理的订单、回复或供货事实；外部投递失败只表示提醒没有送达外部渠道，不会新增或消除业务待办。三类独立统计，不合并为同一个总数。");
        result.put("checks", checks);
        return result;
    }

    public List<Map<String, Object>> exceptions(Integer requestedLimit) {
        Long tenantId = requireTenantId();
        int limit = Math.max(1, Math.min(requestedLimit == null ? 100 : requestedLimit, 200));
        // 统一异常列表按发生时间排序，运营人员无需跨页面定位失败原因。
        return jdbcTemplate.queryForList("""
                SELECT exception_type AS exceptionType,
                       exception_id AS exceptionId,
                       exception_version AS exceptionVersion,
                       account_id AS accountId,
                       target_id AS targetId,
                       title,
                       reason,
                       status,
                       occurred_at AS occurredAt
                FROM (
                    SELECT 'DELIVERY' AS exception_type,
                           source.id AS exception_id,
                           source.exception_revision AS exception_version,
                           source.xianyu_account_id AS account_id,
                           source.order_id AS target_id,
                           COALESCE(source.goods_title, '自动发货订单') AS title,
                           COALESCE(source.last_error_message, source.fail_reason, '等待人工核对') AS reason,
                           source.delivery_status AS status,
                           source.create_time AS occurred_at
                    FROM xianyu_goods_order source
                    WHERE source.tenant_id = ? AND source.delivery_status IN ('FAILED', 'REVIEW_REQUIRED')
                      AND NOT EXISTS (
                        SELECT 1 FROM xianyu_exception_acknowledgement ack
                        WHERE ack.tenant_id = source.tenant_id
                          AND ack.exception_type = 'DELIVERY'
                          AND ack.source_id = source.id
                          AND ack.source_version = source.exception_revision
                      )
                    UNION ALL
                    SELECT 'AUTO_REPLY',
                           source.id,
                           source.exception_revision,
                           source.xianyu_account_id,
                           source.pnm_id,
                           '自动回复失败',
                           COALESCE(source.last_error_message, '回复发送失败'),
                           'FAILED',
                           source.create_time
                    FROM xianyu_goods_auto_reply_record source
                    WHERE source.tenant_id = ? AND source.state IN (-1,3)
                      AND NOT EXISTS (
                        SELECT 1 FROM xianyu_exception_acknowledgement ack
                        WHERE ack.tenant_id = source.tenant_id
                          AND ack.exception_type = 'AUTO_REPLY'
                          AND ack.source_id = source.id
                          AND ack.source_version = source.exception_revision
                      )
                    UNION ALL
                    SELECT 'EXTERNAL_SUPPLY',
                           source.id,
                           source.exception_revision,
                           source.xianyu_account_id,
                           source.order_id,
                           '外部卡密供货待核对',
                           COALESCE(source.error_message, '请求状态不确定'),
                           source.request_status,
                           source.create_time
                    FROM xianyu_kami_external_request source
                    WHERE source.tenant_id = ? AND (
                      source.request_status IN ('FAILED', 'REVIEW_REQUIRED')
                      OR (source.request_status = 'PROCESSING' AND source.update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
                    )
                      AND NOT EXISTS (
                        SELECT 1 FROM xianyu_exception_acknowledgement ack
                        WHERE ack.tenant_id = source.tenant_id
                          AND ack.exception_type = 'EXTERNAL_SUPPLY'
                          AND ack.source_id = source.id
                          AND ack.source_version = source.exception_revision
                      )
                ) exceptions
                ORDER BY occurred_at DESC
                LIMIT ?
                """, tenantId, tenantId, tenantId, limit);
    }

    public void acknowledgeException(String exceptionType, Long exceptionId, Integer exceptionVersion) {
        Long tenantId = requireTenantId();
        acknowledgeException(tenantId,
                normalizeReference(new ExceptionReference(exceptionType, exceptionId, exceptionVersion)), true);
    }

    @Transactional
    public int acknowledgeExceptions(List<ExceptionReference> exceptions) {
        Long tenantId = requireTenantId();
        if (exceptions == null || exceptions.isEmpty() || exceptions.size() > 200) {
            throw new IllegalArgumentException("异常列表数量必须在1至200条之间");
        }
        int acknowledged = 0;
        Set<String> processed = new HashSet<>();
        for (ExceptionReference exception : exceptions) {
            ExceptionReference reference = normalizeReference(exception);
            String key = reference.exceptionType() + "-" + reference.exceptionId() + "-" + reference.exceptionVersion();
            if (processed.add(key)) {
                acknowledged += acknowledgeException(tenantId, reference, false);
            }
        }
        return acknowledged;
    }

    private int acknowledgeException(Long tenantId, ExceptionReference reference, boolean failWhenMissing) {
        String normalizedType = reference.exceptionType();
        String sql = switch (normalizedType) {
            case "DELIVERY" -> """
                    INSERT IGNORE INTO xianyu_exception_acknowledgement
                        (tenant_id, exception_type, source_id, source_version, handled_time)
                    SELECT source.tenant_id, 'DELIVERY', source.id, source.exception_revision, NOW(3)
                    FROM xianyu_goods_order source
                    WHERE source.tenant_id = ? AND source.id = ? AND source.exception_revision = ?
                      AND source.delivery_status IN ('FAILED', 'REVIEW_REQUIRED')
                    """;
            case "AUTO_REPLY" -> """
                    INSERT IGNORE INTO xianyu_exception_acknowledgement
                        (tenant_id, exception_type, source_id, source_version, handled_time)
                    SELECT source.tenant_id, 'AUTO_REPLY', source.id, source.exception_revision, NOW(3)
                    FROM xianyu_goods_auto_reply_record source
                    WHERE source.tenant_id = ? AND source.id = ? AND source.exception_revision = ?
                      AND source.state = -1
                    """;
            case "EXTERNAL_SUPPLY" -> """
                    INSERT IGNORE INTO xianyu_exception_acknowledgement
                        (tenant_id, exception_type, source_id, source_version, handled_time)
                    SELECT source.tenant_id, 'EXTERNAL_SUPPLY', source.id, source.exception_revision, NOW(3)
                    FROM xianyu_kami_external_request source
                    WHERE source.tenant_id = ? AND source.id = ? AND source.exception_revision = ?
                      AND (
                        source.request_status IN ('FAILED', 'REVIEW_REQUIRED')
                        OR (source.request_status = 'PROCESSING'
                            AND source.update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
                      )
                    """;
            default -> throw new IllegalArgumentException("不支持的异常类型");
        };
        int inserted = jdbcTemplate.update(
                sql, tenantId, reference.exceptionId(), reference.exceptionVersion());
        if (inserted > 0) {
            return 1;
        }
        if (isAcknowledged(tenantId, reference)) {
            return 0;
        }
        if (failWhenMissing) {
            throw new IllegalArgumentException("异常不存在或状态已恢复");
        }
        return 0;
    }

    private boolean isAcknowledged(Long tenantId, ExceptionReference reference) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM xianyu_exception_acknowledgement
                WHERE tenant_id = ? AND exception_type = ? AND source_id = ? AND source_version = ?
                """, Long.class, tenantId, reference.exceptionType(),
                reference.exceptionId(), reference.exceptionVersion());
        return count != null && count > 0;
    }

    private String normalizeExceptionType(String exceptionType) {
        return exceptionType == null ? "" : exceptionType.trim().toUpperCase(Locale.ROOT);
    }

    private ExceptionReference normalizeReference(ExceptionReference reference) {
        if (reference == null || reference.exceptionId() == null || reference.exceptionId() <= 0
                || reference.exceptionVersion() == null || reference.exceptionVersion() < 0) {
            throw new IllegalArgumentException("异常标识无效");
        }
        String exceptionType = normalizeExceptionType(reference.exceptionType());
        if (!Set.of("DELIVERY", "AUTO_REPLY", "EXTERNAL_SUPPLY").contains(exceptionType)) {
            throw new IllegalArgumentException("不支持的异常类型");
        }
        return new ExceptionReference(exceptionType, reference.exceptionId(), reference.exceptionVersion());
    }

    public record ExceptionReference(String exceptionType, Long exceptionId, Integer exceptionVersion) {
    }

    private Map<String, Object> check(String key, String name, long count, String action,
                                      String impact, String source, String evidenceTime) {
        return check(key, name, count, count > 0 ? "WARNING" : "HEALTHY", action, impact, source, evidenceTime);
    }

    private Map<String, Object> check(String key, String name, long count, String status, String action,
                                      String impact, String source, String evidenceTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("name", name);
        result.put("count", count);
        result.put("status", status);
        result.put("action", action);
        result.put("impact", impact);
        result.put("source", source);
        result.put("evidenceTime", evidenceTime);
        return result;
    }

    private long count(String sql, Long tenantId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("登录状态已失效");
        }
        return tenantId;
    }
}
