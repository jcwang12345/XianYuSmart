package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 根据账号真实接入状态返回发布通道，不把预留能力伪装为可用。 */
@Service
public class PublishCapabilityService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final ObjectMapper objectMapper;
    private final PublishQaMockService publishQaMockService;
    private final PlatformWritePolicy platformWritePolicy;

    public PublishCapabilityService(JdbcTemplate jdbcTemplate, AccountAccessService accountAccessService,
                                    ObjectMapper objectMapper, PublishQaMockService publishQaMockService,
                                    PlatformWritePolicy platformWritePolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.objectMapper = objectMapper;
        this.publishQaMockService = publishQaMockService;
        this.platformWritePolicy = platformWritePolicy;
    }

    public Map<String, Object> capabilities(Long accountId) {
        accountAccessService.requireAccess(accountId);
        ensureAccount(accountId);
        List<Map<String, Object>> channels = jdbcTemplate.query("""
                SELECT channel_code, channel_name, connection_status, authorization_status,
                       authorization_scope, credential_expire_time, capabilities_json,
                       source, coverage_status, last_checked_time, last_error_message
                  FROM xianyu_account_access_channel
                 WHERE tenant_id=? AND xianyu_account_id=? ORDER BY id
                """, (rs, rowNum) -> channel(rs), tenant(), accountId);
        Map<String, Object> storedQrCookie = channels.stream()
                .filter(channel -> "QR_COOKIE".equals(channel.get("channelCode")))
                .findFirst().orElse(null);
        Map<String, Object> runtimeQrCookie = runtimeQrCookieChannel(accountId, storedQrCookie);
        if (runtimeQrCookie != null) {
            List<Map<String, Object>> effective = new ArrayList<>();
            effective.add(runtimeQrCookie);
            channels.stream()
                    .filter(channel -> !"QR_COOKIE".equals(channel.get("channelCode")))
                    .forEach(effective::add);
            channels = effective;
        }
        boolean hasOfficial = channels.stream().anyMatch(channel -> "OFFICIAL_OAUTH".equals(channel.get("channelCode")));
        if (!hasOfficial) {
            Map<String, Object> reserved = new LinkedHashMap<>();
            reserved.put("channelCode", "OFFICIAL_OAUTH");
            reserved.put("channelName", "官方授权通道");
            reserved.put("available", false);
            reserved.put("executionAvailable", false);
            reserved.put("connectionStatus", "UNKNOWN");
            reserved.put("authorizationStatus", "NOT_CONNECTED");
            reserved.put("coverageStatus", "UNSYNCED");
            reserved.put("reason", "当前账号尚未接入官方授权，不可选择");
            reserved.put("features", featureMatrix("OFFICIAL_OAUTH", false, Map.of()));
            channels = new java.util.ArrayList<>(channels);
            channels.add(reserved);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", accountId);
        response.put("channels", channels);
        response.put("availableChannelCodes", channels.stream()
                .filter(channel -> Boolean.TRUE.equals(channel.get("available")))
                .map(channel -> String.valueOf(channel.get("channelCode"))).toList());
        response.put("executableChannelCodes", channels.stream()
                .filter(channel -> Boolean.TRUE.equals(channel.get("executionAvailable")))
                .map(channel -> String.valueOf(channel.get("channelCode"))).toList());
        response.put("notice", "available=true 代表可执行真实只读预检；只有 executionAvailable=true 才可创建发布任务。未知或未授权能力不会默认开启。");
        return response;
    }

    public String requireAvailableChannel(Long accountId, String requestedChannel) {
        String channelCode = requestedChannel == null || requestedChannel.isBlank()
                ? "QR_COOKIE" : requestedChannel.trim().toUpperCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilities(accountId).get("channels");
        Map<String, Object> channel = channels.stream()
                .filter(value -> channelCode.equals(value.get("channelCode"))).findFirst()
                .orElseThrow(() -> new BusinessException(400, "发布通道不存在：" + channelCode));
        if (!Boolean.TRUE.equals(channel.get("available"))) {
            throw new BusinessException(409, "发布通道当前不可用：" + channel.get("reason"));
        }
        return channelCode;
    }

    public String requireExecutableChannel(Long accountId, String requestedChannel) {
        String channelCode = requireAvailableChannel(accountId, requestedChannel);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilities(accountId).get("channels");
        Map<String, Object> channel = channels.stream()
                .filter(value -> channelCode.equals(value.get("channelCode"))).findFirst()
                .orElseThrow(() -> new BusinessException(400, "发布通道不存在：" + channelCode));
        if (!Boolean.TRUE.equals(channel.get("executionAvailable"))) {
            throw new BusinessException(409, "发布通道当前只允许预检：" + channel.get("executionReason"));
        }
        return channelCode;
    }

    private Map<String, Object> channel(ResultSet rs) throws SQLException {
        String code = rs.getString("channel_code");
        String connection = rs.getString("connection_status");
        String authorization = rs.getString("authorization_status");
        boolean authOkay = "NOT_APPLICABLE".equals(authorization) || "AUTHORIZED".equals(authorization);
        Map<String, Object> discovered = readCapabilities(rs.getString("capabilities_json"));
        boolean adapterAvailable = switch (code) {
            case "QR_COOKIE" -> true;
            case "QA_LOCAL" -> publishQaMockService.enabled()
                    && "MOCK_ONLY".equals(discovered.get("publishing"));
            default -> "SUPPORTED".equals(discovered.get("publishing"));
        };
        boolean available = "CONNECTED".equals(connection) && authOkay && adapterAvailable;
        boolean executionAvailable = available
                && ("QA_LOCAL".equals(code) || platformWritePolicy.enabled());
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelCode", code);
        channel.put("channelName", rs.getString("channel_name"));
        channel.put("available", available);
        channel.put("executionAvailable", executionAvailable);
        channel.put("connectionStatus", connection);
        channel.put("authorizationStatus", authorization);
        channel.put("authorizationScope", rs.getString("authorization_scope"));
        channel.put("credentialExpireTime", instant(rs, "credential_expire_time"));
        channel.put("source", rs.getString("source"));
        channel.put("coverageStatus", rs.getString("coverage_status"));
        channel.put("lastCheckedTime", instant(rs, "last_checked_time"));
        channel.put("reason", available ? null : reason(connection, authorization, rs.getString("last_error_message")));
        channel.put("executionReason", executionAvailable ? null : available
                ? "当前运行环境关闭真实平台写入，只允许平台预检"
                : channel.get("reason"));
        channel.put("features", featureMatrix(code, available, discovered));
        return channel;
    }

    private Map<String, Object> runtimeQrCookieChannel(Long accountId, Map<String, Object> storedChannel) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT account.status accountStatus,
                       cookie.cookie_status cookieStatus,
                       cookie.token_expire_time tokenExpireTime,
                       cookie.expire_time credentialExpireTime,
                       cookie.updated_time lastCheckedTime
                  FROM xianyu_account account
                  LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id=account.id
                 WHERE account.tenant_id=? AND account.id=?
                """, tenant(), accountId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long accountStatus = number(row.get("accountStatus"));
        Long cookieStatus = nullableNumber(row.get("cookieStatus"));
        Instant expiresAt = credentialExpiry(row);
        boolean expiredByTime = expiresAt != null && !expiresAt.isAfter(Instant.now());
        boolean credentialReady = accountStatus == 1 && cookieStatus != null && cookieStatus == 1 && !expiredByTime;
        String connection = credentialReady ? "CONNECTED"
                : accountStatus == -2 ? "NEEDS_VERIFICATION"
                : expiredByTime || (cookieStatus != null && (cookieStatus == 2 || cookieStatus == 3)) ? "EXPIRED"
                : cookieStatus == null ? "DISCONNECTED" : "DEGRADED";
        String reason = switch (connection) {
            case "NEEDS_VERIFICATION" -> "账号需要安全验证，请在连接管理重新扫码续期";
            case "EXPIRED" -> "扫码/Cookie 凭据已过期，请重新扫码续期";
            case "DISCONNECTED" -> "未找到扫码/Cookie 登录凭据";
            case "DEGRADED" -> "账号未处于可运行状态，请先恢复连接";
            default -> null;
        };
        boolean executionAvailable = credentialReady && platformWritePolicy.enabled();
        Map<String, Object> features = new LinkedHashMap<>(featureMatrix("QR_COOKIE", credentialReady, Map.of()));
        if (credentialReady && storedChannel != null && storedChannel.get("features") instanceof Map<?, ?> storedFeatures) {
            storedFeatures.forEach((key, value) -> features.put(String.valueOf(key), value));
        }
        features.put("execution", executionAvailable ? "READY" : credentialReady ? "ENVIRONMENT_BLOCKED" : "UNAVAILABLE");
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelCode", "QR_COOKIE");
        channel.put("channelName", "扫码/Cookie 通道");
        channel.put("available", credentialReady);
        channel.put("executionAvailable", executionAvailable);
        channel.put("connectionStatus", connection);
        channel.put("authorizationStatus", "NOT_APPLICABLE");
        channel.put("authorizationScope", "个人号商品预检与单品发布；高级字段仍按能力矩阵阻断");
        channel.put("credentialExpireTime", expiresAt);
        channel.put("source", "LOCAL_RUNTIME");
        channel.put("coverageStatus", cookieStatus == null ? "PARTIAL" : "FULL");
        channel.put("lastCheckedTime", instantValue(row.get("lastCheckedTime")));
        channel.put("reason", reason);
        channel.put("executionReason", executionAvailable ? null : credentialReady
                ? "当前运行环境关闭真实平台写入，只允许平台预检"
                : reason);
        channel.put("recoveryRoute", "/connection?accountId=" + accountId + "&renew=1");
        channel.put("features", features);
        return channel;
    }

    Map<String, Object> featureMatrix(String channelCode, boolean available,
                                      Map<String, Object> discovered) {
        boolean official = "OFFICIAL_OAUTH".equals(channelCode);
        boolean qrCookie = "QR_COOKIE".equals(channelCode);
        String publishing = capability(discovered, "publishing", available ? "READY" : "UNAVAILABLE");
        String products = capability(discovered, "products", available ? "PARTIAL" : "UNAVAILABLE");
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("publishing", publishing);
        features.put("category", available ? "READY" : "UNAVAILABLE");
        features.put("edit", official ? products : available ? "PARTIAL" : "UNAVAILABLE");
        // 当前 PC 发布适配器只构造单 SKU。没有探测证据时必须阻止多规格，不能显示 READY。
        features.put("sku", capability(discovered, "sku", available ? "NOT_VERIFIED" : "UNAVAILABLE"));
        features.put("bargain", capability(discovered, "marketing",
                official && available ? "UNKNOWN" : qrCookie ? "REQUIRES_PLATFORM_PERMISSION" : "NOT_VERIFIED"));
        features.put("sync", products);
        features.put("authorization", official ? (available ? "AUTHORIZED" : "NOT_CONNECTED") : "NOT_APPLICABLE");
        return features;
    }

    private Map<String, Object> readCapabilities(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String capability(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    String reason(String connection, String authorization, String error) {
        if (error != null && !error.isBlank()) return error;
        if (!"CONNECTED".equals(connection)) return "连接状态为" + connection;
        return "授权状态为" + authorization;
    }

    private void ensureAccount(Long accountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xianyu_account WHERE tenant_id=? AND id=?", Integer.class, tenant(), accountId);
        if (count == null || count == 0) throw new BusinessException(404, "账号不存在或不属于当前经营主体");
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Long nullableNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Instant credentialExpiry(Map<String, Object> row) {
        Long tokenExpireTime = nullableNumber(row.get("tokenExpireTime"));
        if (tokenExpireTime != null && tokenExpireTime > 0) return Instant.ofEpochMilli(tokenExpireTime);
        return instantValue(row.get("credentialExpireTime"));
    }

    private Instant instantValue(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Instant instant) return instant;
        return null;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
