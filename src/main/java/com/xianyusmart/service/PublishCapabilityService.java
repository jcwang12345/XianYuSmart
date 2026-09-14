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

    public PublishCapabilityService(JdbcTemplate jdbcTemplate, AccountAccessService accountAccessService,
                                    ObjectMapper objectMapper, PublishQaMockService publishQaMockService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.objectMapper = objectMapper;
        this.publishQaMockService = publishQaMockService;
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
        boolean hasOfficial = channels.stream().anyMatch(channel -> "OFFICIAL_OAUTH".equals(channel.get("channelCode")));
        if (!hasOfficial) {
            Map<String, Object> reserved = new LinkedHashMap<>();
            reserved.put("channelCode", "OFFICIAL_OAUTH");
            reserved.put("channelName", "官方授权通道");
            reserved.put("available", false);
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
        response.put("notice", "仅 available=true 的通道可发布；未知或未授权能力不会默认开启。");
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
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelCode", code);
        channel.put("channelName", rs.getString("channel_name"));
        channel.put("available", available);
        channel.put("connectionStatus", connection);
        channel.put("authorizationStatus", authorization);
        channel.put("authorizationScope", rs.getString("authorization_scope"));
        channel.put("credentialExpireTime", instant(rs, "credential_expire_time"));
        channel.put("source", rs.getString("source"));
        channel.put("coverageStatus", rs.getString("coverage_status"));
        channel.put("lastCheckedTime", instant(rs, "last_checked_time"));
        channel.put("reason", available ? null : reason(connection, authorization, rs.getString("last_error_message")));
        channel.put("features", featureMatrix(code, available, discovered));
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

    private String reason(String connection, String authorization, String error) {
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

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
