package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.DeliveryUncertainException;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.reply.HumanTakeoverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageWorkspaceServiceTest {

    private JdbcTemplate jdbcTemplate;
    private WebSocketService webSocketService;
    private SentMessageSaveService sentMessageSaveService;
    private AiHandoffService aiHandoffService;
    private OperationLogService operationLogService;
    private MessageWorkspaceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        webSocketService = mock(WebSocketService.class);
        sentMessageSaveService = mock(SentMessageSaveService.class);
        aiHandoffService = mock(AiHandoffService.class);
        operationLogService = mock(OperationLogService.class);
        service = new MessageWorkspaceService(jdbcTemplate, mock(AccountAccessService.class),
                mock(ConversationAssignmentService.class), webSocketService, sentMessageSaveService,
                mock(HumanTakeoverManager.class), operationLogService,
                aiHandoffService, new ObjectMapper());
        TenantContext.set(6L);
        UserContext.set(4L, "message-tester", 6L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("INSERT IGNORE INTO xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void acknowledgementTimeoutBecomesUnknownAndIsNotSavedAsSent() {
        when(webSocketService.isConnected(9L)).thenReturn(true);
        when(webSocketService.sendMessageWithResult(9L, "session-1", "buyer-1", "你好"))
                .thenThrow(new DeliveryUncertainException("文本消息回执超时，送达结果未知"));

        Map<String, Object> result = service.sendText(command("request-1"));

        assertEquals("UNKNOWN", result.get("outcomeState"));
        assertTrue(String.valueOf(result.get("recoveryHint")).contains("不要重复发送"));
        verify(sentMessageSaveService, never()).saveManualReply(any(), anyString(), anyString(), anyString(), any());
        verify(aiHandoffService).open(any(AiHandoffService.OpenCommand.class));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("outcome_state=?"),
                eq("UNKNOWN"), any(), any(), any(), eq("PENDING_VERIFICATION"), eq(6L), eq("request-1"));
    }

    @Test
    void duplicateRequestReturnsPersistedOutcomeWithoutSecondPlatformSend() {
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcomeState", "UNKNOWN");
        replay.put("errorMessage", "等待核查");
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(List.of(replay));

        Map<String, Object> result = service.sendText(command("request-2"));

        assertEquals(true, result.get("idempotentReplay"));
        assertEquals("UNKNOWN", result.get("outcomeState"));
        verify(webSocketService, never()).sendMessageWithResult(any(), anyString(), anyString(), anyString());
    }

    @Test
    void concurrentDuplicateInsertReadsWinnerWithoutSecondPlatformSend() {
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcomeState", "SENT");
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(List.of(), List.of(replay));
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("INSERT IGNORE INTO xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(0);

        Map<String, Object> result = service.sendText(command("request-concurrent"));

        assertEquals(true, result.get("idempotentReplay"));
        assertEquals("SENT", result.get("outcomeState"));
        verify(webSocketService, never()).sendMessageWithResult(any(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateRequestWithDifferentPayloadIsRejectedBeforePlatformSend() {
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcomeState", "UNKNOWN");
        replay.put("requestPayloadHash", "different-payload-hash");
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(List.of(replay));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.sendText(command("request-bound")));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("已绑定另一条消息"));
        verify(webSocketService, never()).sendMessageWithResult(any(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownResolutionPreviewNeverSendsAndExecutePersistsManualEvidence() {
        Map<String,Object> unknown = unknownAttempt();
        Map<String,Object> resolved = new LinkedHashMap<>(unknown);
        resolved.put("outcomeState", "MANUAL_CONFIRMED_SENT");
        resolved.put("resolutionStatus", "CONFIRMED_SENT");
        resolved.put("resolutionRequestId", "resolve-request-1");
        resolved.put("verifiedMessageId", "platform-message-9");
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FROM xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(List.of(unknown), List.of(unknown), List.of(resolved));
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("resolution_status=?"),
                any(Object[].class))).thenReturn(1);
        var command = new MessageWorkspaceService.SendResolutionCommand(9L, "CONFIRMED_SENT",
                "已在闲鱼会话逐字核对", "platform-message-9", "resolve-request-1");

        Map<String,Object> preview = service.previewResolution("send-request-1", command);
        Map<String,Object> result = service.resolveAttempt("send-request-1", command);

        assertEquals(false, preview.get("willResend"));
        assertEquals("MANUAL_CONFIRMED_SENT", result.get("outcomeState"));
        verify(webSocketService, never()).sendMessageWithResult(any(), anyString(), anyString(), anyString());
        verify(aiHandoffService).resolveMessageOutcome("send-request-1", "resolve-request-1",
                "CONFIRMED_SENT：已在闲鱼会话逐字核对");
        verify(operationLogService).logRequired(any());
    }

    @Test
    void confirmedSentRequiresVerifiableMessageId() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FROM xianyu_message_send_attempt"),
                any(Object[].class))).thenReturn(List.of(unknownAttempt()));
        var command = new MessageWorkspaceService.SendResolutionCommand(9L, "CONFIRMED_SENT",
                "看到了", null, "resolve-request-2");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.previewResolution("send-request-2", command));

        assertEquals(400, error.getCode());
        verify(webSocketService, never()).sendMessageWithResult(any(), anyString(), anyString(), anyString());
    }

    @Test
    void conversationBlacklistProjectsStatusAndSourceIntoBuyerProfile() {
        when(jdbcTemplate.queryForMap(org.mockito.ArgumentMatchers.contains("buyer_user_id"),
                any(Object[].class))).thenReturn(Map.of("buyerUserId", "buyer-1"));

        Map<String, Object> result = service.updateConversation(new MessageWorkspaceService.ConversationUpdate(
                9L, "session-1", false, "NONE", "高风险客户", true, "request-blacklist"));

        assertEquals(true, result.get("blacklisted"));
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("blacklist_source='MESSAGE_WORKSPACE'"),
                any(Object[].class));
    }

    @Test
    void conversationUnblacklistPreservesUnrelatedManualAutomationBlock() {
        when(jdbcTemplate.queryForMap(org.mockito.ArgumentMatchers.contains("buyer_user_id"),
                any(Object[].class))).thenReturn(Map.of("buyerUserId", "buyer-1"));

        service.updateConversation(new MessageWorkspaceService.ConversationUpdate(
                9L, "session-1", false, "NONE", null, false, "request-unblacklist"));

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains(
                        "automation_blocked=CASE WHEN blocked_reason LIKE '[会话]%'"),
                any(Object[].class));
    }

    private MessageWorkspaceService.SendCommand command(String requestId) {
        return new MessageWorkspaceService.SendCommand(9L, "session-1", "buyer-1", "goods-1",
                "你好", null, null, requestId);
    }

    private Map<String,Object> unknownAttempt() {
        Map<String,Object> attempt = new LinkedHashMap<>();
        attempt.put("accountId", 9L);
        attempt.put("sessionId", "session-1");
        attempt.put("requestId", "send-request-1");
        attempt.put("outcomeState", "UNKNOWN");
        attempt.put("resolutionStatus", "PENDING_VERIFICATION");
        attempt.put("resolutionRequestId", null);
        attempt.put("resolutionPayloadHash", null);
        attempt.put("platformReceiptJson", null);
        return attempt;
    }
}
