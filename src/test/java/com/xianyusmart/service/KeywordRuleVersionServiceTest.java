package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeywordRuleVersionServiceTest {
    private JdbcTemplate jdbc;
    private AccountAccessService access;
    private OperationLogService audit;
    private KeywordRuleVersionService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class); access = mock(AccountAccessService.class); audit = mock(OperationLogService.class);
        service = new KeywordRuleVersionService(jdbc, access, audit, new ObjectMapper().findAndRegisterModules());
        TenantContext.set(6L); UserContext.set(7L, "keyword-tester", 6L);
    }

    @AfterEach
    void clear() { TenantContext.clear(); UserContext.clear(); }

    @Test
    void invalidRegexIsRejectedBeforeAnyWrite() {
        var command = command("[未闭合", "REGEX", "rule-invalid");

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(9L, command));

        assertEquals(400, error.getCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void enabledRuleRequiresAtLeastOneReply() {
        var command = new KeywordRuleVersionService.SaveCommand("安装", "CONTAINS", 100, true,
                LocalDateTime.now(), null, List.of(9L), List.of(), "rule-no-content");

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(9L, command));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("至少配置一条"));
    }

    @Test
    void sameRequestWithDifferentPayloadIsRejected() {
        when(jdbc.queryForList(contains("request_payload_hash requestPayloadHash"), any(Object[].class)))
                .thenReturn(List.of(Map.of("requestPayloadHash", "not-the-same")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.save(9L, command("安装", "CONTAINS", "rule-replay")));

        assertEquals(409, error.getCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void saveCreatesLegacySnapshotAndNewImmutableVersion() {
        Map<String,Object> rule = new LinkedHashMap<>();
        rule.put("id", 9L); rule.put("accountId", 9L); rule.put("goodsId", "goods-1");
        rule.put("keyword", "旧关键词"); rule.put("matchType", "CONTAINS"); rule.put("priority", 100);
        rule.put("enabled", 1); rule.put("versionNo", 1); rule.put("isFallback", 0);
        rule.put("sharingScope", "GOODS"); rule.put("effectiveTime", LocalDateTime.now().minusDays(1));
        rule.put("expiresTime", null);
        when(jdbc.queryForList(contains("request_payload_hash requestPayloadHash"), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM xianyu_keyword_reply_rule WHERE"), any(Object[].class))).thenReturn(List.of(rule));
        when(jdbc.queryForObject(contains("xianyu_account WHERE"), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("xianyu_keyword_reply_rule_version WHERE"), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(contains("SELECT xianyu_account_id"), eq(Long.class), any(Object[].class))).thenReturn(List.of(9L));
        when(jdbc.queryForList(contains("reply_text replyText"), any(Object[].class))).thenReturn(List.of());
        Map<String,Object> version = new LinkedHashMap<>();
        version.put("id", 2L); version.put("ruleId", 9L); version.put("versionNo", 2);
        version.put("keyword", "安装"); version.put("matchType", "EXACT"); version.put("priority", 300);
        version.put("enabled", 1); version.put("isFallback", 0); version.put("sharingScope", "GOODS");
        version.put("accountIdsJson", "[9]"); version.put("contentsJson", "[{\"replyText\":\"请看教程\"}]");
        version.put("requestId", "rule-save");
        when(jdbc.queryForMap(contains("FROM xianyu_keyword_reply_rule_version WHERE"), any(Object[].class))).thenReturn(version);

        Map<String,Object> result = service.save(9L, new KeywordRuleVersionService.SaveCommand(
                "安装", "EXACT", 300, true, LocalDateTime.now(), null, List.of(9L),
                List.of(new KeywordRuleVersionService.ContentCommand("请看教程", null)), "rule-save"));

        assertEquals(2, result.get("versionNo"));
        assertEquals(false, result.get("idempotentReplay"));
        verify(jdbc).update(contains("UPDATE xianyu_keyword_reply_rule"), any(Object[].class));
        verify(jdbc, times(2)).update(contains("INSERT INTO xianyu_keyword_reply_rule_version"), any(Object[].class));
        verify(audit).logRequired(any());
    }

    private KeywordRuleVersionService.SaveCommand command(String keyword, String type, String requestId) {
        return new KeywordRuleVersionService.SaveCommand(keyword, type, 100, true,
                LocalDateTime.now(), null, List.of(9L),
                List.of(new KeywordRuleVersionService.ContentCommand("回复内容", null)), requestId);
    }
}
