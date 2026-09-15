package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishCapabilityServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AccountAccessService accountAccessService;
    private PublishQaMockService publishQaMockService;
    private PlatformWritePolicy platformWritePolicy;
    private PublishCapabilityService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        publishQaMockService = mock(PublishQaMockService.class);
        platformWritePolicy = mock(PlatformWritePolicy.class);
        when(platformWritePolicy.enabled()).thenReturn(true);
        service = new PublishCapabilityService(jdbcTemplate, accountAccessService, new ObjectMapper(), publishQaMockService, platformWritePolicy);
        TenantContext.set(7L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void officialChannelIsReservedButCannotBeSelectedWithoutAuthorization() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(connectedQrChannel()));

        Map<String, Object> capabilities = service.capabilities(9L);

        assertEquals(List.of("QR_COOKIE"), capabilities.get("availableChannelCodes"));
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilities.get("channels");
        Map<String, Object> official = channels.stream()
                .filter(channel -> "OFFICIAL_OAUTH".equals(channel.get("channelCode")))
                .findFirst().orElseThrow();
        assertEquals(false, official.get("available"));
        assertEquals("UNSYNCED", official.get("coverageStatus"));
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.requireAvailableChannel(9L, "OFFICIAL_OAUTH"));
        assertEquals(409, denied.getCode());
        verify(accountAccessService, org.mockito.Mockito.atLeastOnce()).requireAccess(9L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void connectedQrChannelIsTheOnlyDefaultPublishChoice() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(connectedQrChannel()));

        assertEquals("QR_COOKIE", service.requireAvailableChannel(9L, ""));
    }

    @Test
    void skuIsNotAdvertisedAsReadyWithoutProbeEvidence() {
        Map<String, Object> features = service.featureMatrix("QR_COOKIE", true, Map.of());

        assertEquals("READY", features.get("publishing"));
        assertEquals("NOT_VERIFIED", features.get("sku"));
    }

    @Test
    void explicitCapabilityEvidenceIsPreserved() {
        Map<String, Object> features = service.featureMatrix("OFFICIAL_OAUTH", true,
                Map.of("publishing", "SUPPORTED", "products", "FULL", "sku", "SUPPORTED"));

        assertEquals("SUPPORTED", features.get("publishing"));
        assertEquals("FULL", features.get("sync"));
        assertEquals("SUPPORTED", features.get("sku"));
    }

    @Test
    void utf8FailureReasonIsReturnedWithoutReencoding() {
        assertEquals("部分平台字段未同步",
                service.reason("DEGRADED", "NOT_APPLICABLE", "部分平台字段未同步"));
        assertEquals("隔离环境模拟凭据过期",
                service.reason("EXPIRED", "EXPIRED", "隔离环境模拟凭据过期"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void runtimeQrCookieExplainsVerificationRequiredInsteadOfDisappearing() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "accountStatus", -2,
                "cookieStatus", 1,
                "tokenExpireTime", System.currentTimeMillis() + 60_000,
                "lastCheckedTime", java.sql.Timestamp.from(java.time.Instant.now())
        )));

        Map<String, Object> capabilities = service.capabilities(9L);
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilities.get("channels");
        Map<String, Object> qr = channels.stream()
                .filter(channel -> "QR_COOKIE".equals(channel.get("channelCode"))).findFirst().orElseThrow();

        assertEquals(false, qr.get("available"));
        assertEquals("NEEDS_VERIFICATION", qr.get("connectionStatus"));
        assertTrue(String.valueOf(qr.get("reason")).contains("重新扫码续期"));
        assertEquals("LOCAL_RUNTIME", qr.get("source"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void validRuntimeCredentialCanPreflightButQaEnvironmentCannotExecute() {
        when(platformWritePolicy.enabled()).thenReturn(false);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "accountStatus", 1,
                "cookieStatus", 1,
                "tokenExpireTime", System.currentTimeMillis() + 60_000,
                "lastCheckedTime", java.sql.Timestamp.from(java.time.Instant.now())
        )));

        Map<String, Object> capabilities = service.capabilities(9L);
        List<Map<String, Object>> channels = (List<Map<String, Object>>) capabilities.get("channels");
        Map<String, Object> qr = channels.stream()
                .filter(channel -> "QR_COOKIE".equals(channel.get("channelCode"))).findFirst().orElseThrow();

        assertEquals(true, qr.get("available"));
        assertEquals(false, qr.get("executionAvailable"));
        assertEquals(List.of("QR_COOKIE"), capabilities.get("availableChannelCodes"));
        assertEquals(List.of(), capabilities.get("executableChannelCodes"));
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.requireExecutableChannel(9L, "QR_COOKIE"));
        assertEquals(409, denied.getCode());
        assertTrue(denied.getMessage().contains("只允许预检"));
    }

    private Map<String, Object> connectedQrChannel() {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelCode", "QR_COOKIE");
        channel.put("channelName", "扫码/Cookie 接入");
        channel.put("available", true);
        channel.put("connectionStatus", "CONNECTED");
        channel.put("authorizationStatus", "NOT_APPLICABLE");
        channel.put("coverageStatus", "PARTIAL");
        channel.put("features", Map.of("sync", "PARTIAL"));
        return channel;
    }
}
