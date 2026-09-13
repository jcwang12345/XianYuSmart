package com.xianyusmart.service;

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

    public PublishCapabilityService(JdbcTemplate jdbcTemplate, AccountAccessService accountAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
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
            reserved.put("features", featureMatrix(false, false));
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
        boolean available = "CONNECTED".equals(connection) && authOkay;
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
        channel.put("features", featureMatrix("OFFICIAL_OAUTH".equals(code), available));
        return channel;
    }

    private Map<String, Object> featureMatrix(boolean official, boolean available) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("category", available ? "READY" : "UNAVAILABLE");
        features.put("edit", official && available ? "UNKNOWN" : "NOT_VERIFIED");
        features.put("sku", available ? "READY" : "UNAVAILABLE");
        features.put("bargain", official && available ? "UNKNOWN" : "REQUIRES_PLATFORM_PERMISSION");
        features.put("sync", available ? "PARTIAL" : "UNAVAILABLE");
        features.put("authorization", official ? (available ? "AUTHORIZED" : "NOT_CONNECTED") : "NOT_APPLICABLE");
        return features;
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
