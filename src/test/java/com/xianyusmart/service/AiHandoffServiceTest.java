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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiHandoffServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AccountAccessService accountAccessService;
    private OperationLogService operationLogService;
    private AiHandoffService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        accountAccessService = mock(AccountAccessService.class);
        operationLogService = mock(OperationLogService.class);
        service = new AiHandoffService(jdbcTemplate, accountAccessService, operationLogService, new ObjectMapper());
        TenantContext.set(6L);
        UserContext.set(4L, "handoff-tester", 6L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void openingTaskPersistsConversationStateAndAudit() {
        when(jdbcTemplate.update(contains("INSERT INTO xianyu_ai_handoff_task"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForMap(contains("FROM xianyu_ai_handoff_task"), any(Object[].class)))
                .thenReturn(new LinkedHashMap<>(Map.of(
                        "id", 31L, "accountId", 9L, "sessionId", "session-1", "status", "OPEN",
                        "reasonCode", "AI_NO_SAFE_ANSWER", "requestId", "handoff-request")));

        Map<String, Object> result = service.open(command("AI_NO_SAFE_ANSWER", 0.41));

        assertEquals(31L, result.get("id"));
        assertEquals("AI 未生成可安全发送的内容", result.get("reasonLabel"));
        assertEquals(false, result.get("idempotentReplay"));
        verify(accountAccessService).requireAccess(9L);
        verify(jdbcTemplate).update(contains("auto_reply_state='HUMAN_REQUIRED'"), any(Object[].class));
        verify(operationLogService).log(any(XianyuOperationLog.class));
    }

    @Test
    void duplicateOpenIsReportedAsIdempotentReplay() {
        when(jdbcTemplate.update(contains("INSERT INTO xianyu_ai_handoff_task"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForMap(contains("FROM xianyu_ai_handoff_task"), any(Object[].class)))
                .thenReturn(new LinkedHashMap<>(Map.of(
                        "id", 31L, "accountId", 9L, "sessionId", "session-1", "status", "OPEN",
                        "reasonCode", "AI_NO_SAFE_ANSWER", "requestId", "handoff-request")));

        Map<String, Object> result = service.open(command("AI_NO_SAFE_ANSWER", null));

        assertEquals(true, result.get("idempotentReplay"));
    }

    @Test
    void claimUsesAtomicOpenStateGuard() {
        Map<String, Object> open = task("OPEN", null);
        Map<String, Object> claimed = task("CLAIMED", 4L);
        when(jdbcTemplate.queryForList(contains("SELECT id,xianyu_account_id"), any(Object[].class)))
                .thenReturn(List.of(open), List.of(claimed));
        when(jdbcTemplate.update(contains("SET status='CLAIMED'"), any(Object[].class))).thenReturn(1);

        Map<String, Object> result = service.claim(31L, new AiHandoffService.ActionCommand(null, null, "claim-request"));

        assertEquals("CLAIMED", result.get("status"));
        assertEquals(false, result.get("idempotentReplay"));
        verify(accountAccessService, times(2)).requireAccess(9L);
    }

    @Test
    void ignoreRequiresAnAuditableReason() {
        when(jdbcTemplate.queryForList(contains("SELECT id,xianyu_account_id"), any(Object[].class)))
                .thenReturn(List.of(task("OPEN", null)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.resolve(31L,
                new AiHandoffService.ActionCommand("IGNORED", "  ", "ignore-request")));

        assertTrue(error.getMessage().contains("忽略任务必须填写原因"));
    }

    @Test
    void pendingCheckReadsPersistentTaskState() {
        when(jdbcTemplate.queryForObject(contains("status IN ('OPEN','CLAIMED')"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.hasPending(9L, "session-1"));
    }

    private AiHandoffService.OpenCommand command(String reasonCode, Double confidence) {
        return new AiHandoffService.OpenCommand(9L, "session-1", "goods-1", "buyer-1", null,
                reasonCode, "QA 转人工说明", confidence, "qa-model", 88L,
                "AUTO_REPLY:9:message-1", "handoff-request");
    }

    private Map<String, Object> task(String status, Long claimedBy) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", 31L);
        task.put("accountId", 9L);
        task.put("sessionId", "session-1");
        task.put("status", status);
        task.put("claimedBy", claimedBy);
        task.put("reasonCode", "AI_NO_SAFE_ANSWER");
        task.put("requestId", "handoff-request");
        return task;
    }
}
