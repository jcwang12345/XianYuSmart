package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoodsKnowledgeServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AccountAccessService accountAccessService;
    private OperationLogService operationLogService;
    private GoodsKnowledgeService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        operationLogService = mock(OperationLogService.class);
        service = new GoodsKnowledgeService(jdbcTemplate, accountAccessService, operationLogService,
                new ObjectMapper(), () -> "test-attempt-token");
        TenantContext.set(8L);
        UserContext.set(12L, "knowledge-tester", 8L);
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*) FROM xianyu_goods"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void createsActiveVersionWithoutRequiringAutomationConfig() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        when(jdbcTemplate.queryForList(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM xianyu_goods\n"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 77L)));
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(version_no)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForMap(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(requestRow(41L, "七天有效，首次激活起算", expires, true,
                        "test-attempt-token", "LEGACY"));
        when(jdbcTemplate.queryForMap(contains("WHERE tenant_id=? AND id=?"), any(Object[].class)))
                .thenReturn(version(41L, 1, "ACTIVE", "七天有效，首次激活起算"));

        Map<String, Object> result = service.save(new GoodsKnowledgeService.SaveCommand(
                101L, "QA-GOODS-1", "七天有效，首次激活起算", null,
                expires, true, "MANUAL", "qa-knowledge-save-1"));

        assertEquals(false, result.get("idempotentReplay"));
        verify(accountAccessService).requireAccess(101L);
        verify(jdbcTemplate).update(contains("INSERT INTO xianyu_goods_knowledge_version"), any(Object[].class));
        verify(operationLogService).log(any(XianyuOperationLog.class));
    }

    @Test
    void sameExactRequestReturnsOriginalVersionWithoutSecondInsert() {
        LocalDateTime expires = LocalDateTime.now().plusDays(1);
        String payloadHash = service.payloadHash(101L, "QA-GOODS-1", "原版本", null,
                expires, true, "MANUAL");
        when(jdbcTemplate.queryForList(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(List.of(requestRow(41L, "原版本", expires, true, "first-token", payloadHash)));
        when(jdbcTemplate.queryForMap(contains("WHERE tenant_id=? AND id=?"), any(Object[].class)))
                .thenReturn(version(41L, 1, "ACTIVE", "原版本"));

        Map<String, Object> result = service.save(new GoodsKnowledgeService.SaveCommand(
                101L, "QA-GOODS-1", "原版本", null, expires, true, "MANUAL", "qa-knowledge-save-1"));

        assertEquals(true, result.get("idempotentReplay"));
        assertEquals("原版本", result.get("content"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO xianyu_goods_knowledge_version"), any(Object[].class));
    }

    @Test
    void sameRequestWithDifferentContentReturnsConflict() {
        LocalDateTime expires = LocalDateTime.now().plusDays(1);
        when(jdbcTemplate.queryForList(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(List.of(requestRow(41L, "原版本", expires, true, "first-token", "LEGACY")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(
                new GoodsKnowledgeService.SaveCommand(101L, "QA-GOODS-1", "不同内容", null,
                        expires, true, "MANUAL", "qa-knowledge-save-1")));

        assertEquals(409, error.getCode());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO xianyu_goods_knowledge_version"), any(Object[].class));
    }

    @Test
    void sameRequestWithDifferentExpiryReturnsConflict() {
        LocalDateTime expires = LocalDateTime.now().plusDays(1);
        when(jdbcTemplate.queryForList(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(List.of(requestRow(41L, "原版本", expires, false, "first-token", "LEGACY")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(
                new GoodsKnowledgeService.SaveCommand(101L, "QA-GOODS-1", "原版本", null,
                        expires.plusHours(1), false, "MANUAL", "qa-knowledge-save-1")));

        assertEquals(409, error.getCode());
    }

    @Test
    void sameRequestWithDifferentActivationModeReturnsConflict() {
        LocalDateTime expires = LocalDateTime.now().plusDays(1);
        when(jdbcTemplate.queryForList(contains("WHERE tenant_id=? AND request_id=?"), any(Object[].class)))
                .thenReturn(List.of(requestRow(41L, "原版本", expires, false, "first-token", "LEGACY")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(
                new GoodsKnowledgeService.SaveCommand(101L, "QA-GOODS-1", "原版本", null,
                        expires, true, "MANUAL", "qa-knowledge-save-1")));

        assertEquals(409, error.getCode());
    }

    @Test
    void rejectsInvalidEffectiveWindowBeforePersisting() {
        LocalDateTime effective = LocalDateTime.now();
        BusinessException error = assertThrows(BusinessException.class, () -> service.save(
                new GoodsKnowledgeService.SaveCommand(101L, "QA-GOODS-1", "内容", effective,
                        effective.minusSeconds(1), false, "MANUAL", "qa-invalid-window")));

        assertTrue(error.getMessage().contains("失效时间必须晚于生效时间"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO xianyu_goods_knowledge_version"), any(Object[].class));
    }

    @Test
    void effectiveReturnsOnlyActiveVersionInsideTimeWindow() {
        LocalDateTime now = LocalDateTime.now();
        when(jdbcTemplate.queryForList(contains("status='ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of(
                        "id", 51L, "versionNo", 4, "content", "当前有效知识",
                        "effectiveTime", now.minusMinutes(1), "expiresTime", now.plusDays(1)))));

        GoodsKnowledgeService.ActiveKnowledge active = service.effective(101L, "QA-GOODS-1");

        assertEquals(51L, active.id());
        assertEquals(4, active.versionNo());
        assertEquals("当前有效知识", active.content());
    }

    private Map<String, Object> version(Long id, int versionNo, String status, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("accountId", 101L);
        row.put("goodsId", "QA-GOODS-1");
        row.put("versionNo", versionNo);
        row.put("content", content);
        row.put("status", status);
        row.put("sourceType", "MANUAL");
        row.put("effectiveTime", LocalDateTime.now().minusMinutes(1));
        row.put("expiresTime", LocalDateTime.now().plusDays(1));
        row.put("requestId", "qa-knowledge-save-1");
        row.put("createdUsername", "knowledge-tester");
        row.put("createdTime", LocalDateTime.now());
        return row;
    }

    private Map<String, Object> requestRow(Long id, String content, LocalDateTime expires,
                                           boolean activated, String attemptToken, String payloadHash) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("accountId", 101L);
        row.put("goodsId", "QA-GOODS-1");
        row.put("content", content);
        row.put("sourceType", "MANUAL");
        row.put("effectiveTime", LocalDateTime.now().minusMinutes(1));
        row.put("expiresTime", expires);
        row.put("activatedTime", activated ? LocalDateTime.now() : null);
        row.put("requestPayloadHash", payloadHash);
        row.put("requestAttemptToken", attemptToken);
        return row;
    }
}
