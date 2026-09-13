package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.DeliveryUncertainException;
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
    private MessageWorkspaceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        webSocketService = mock(WebSocketService.class);
        sentMessageSaveService = mock(SentMessageSaveService.class);
        service = new MessageWorkspaceService(jdbcTemplate, mock(AccountAccessService.class),
                mock(ConversationAssignmentService.class), webSocketService, sentMessageSaveService,
                mock(HumanTakeoverManager.class), mock(OperationLogService.class), new ObjectMapper());
        TenantContext.set(6L);
        UserContext.set(4L, "message-tester", 6L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
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
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("outcome_state=?"),
                eq("UNKNOWN"), any(), any(), eq(6L), eq("request-1"));
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

    private MessageWorkspaceService.SendCommand command(String requestId) {
        return new MessageWorkspaceService.SendCommand(9L, "session-1", "buyer-1", "goods-1",
                "你好", null, null, requestId);
    }
}
