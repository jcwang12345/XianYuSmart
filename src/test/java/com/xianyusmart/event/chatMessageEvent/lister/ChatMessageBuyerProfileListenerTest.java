package com.xianyusmart.event.chatMessageEvent.lister;

import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.BuyerProfileService;
import com.xianyusmart.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatMessageBuyerProfileListenerTest {
    private final BuyerProfileService profiles = mock(BuyerProfileService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final OperationLogService audits = mock(OperationLogService.class);
    private final ChatMessageBuyerProfileListener listener = new ChatMessageBuyerProfileListener(profiles, accounts, audits);

    private ChatMessageReceivedEvent event(String sender) {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(101L);
        message.setSenderUserId(sender);
        message.setSenderUserName("private-name");
        message.setPnmId("QA-R01-failure-message");
        message.setMsgContent("private-content");
        message.setMessageTime(1700000000000L);
        when(accounts.getXianyuUserId(101L)).thenReturn("seller");
        return new ChatMessageReceivedEvent(this, message);
    }

    @Test
    void successfulBuyerProjectionUsesEventTransactionAndDoesNotAuditFailure() {
        listener.handleChatMessageReceived(event("buyer"));
        verify(profiles).touchFromMessage(101L, "buyer", "private-name", 1700000000000L);
        verifyNoInteractions(audits);
    }

    @Test
    void failedProjectionProducesOneRedactedFailureAuditAndNoUncaughtException() {
        doThrow(new CannotAcquireLockException("private-content token=secret"))
                .when(profiles).touchFromMessage(anyLong(), anyString(), anyString(), anyLong());
        assertDoesNotThrow(() -> listener.handleChatMessageReceived(event("buyer")));
        ArgumentCaptor<XianyuOperationLog> captured = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(audits).logRequired(captured.capture());
        XianyuOperationLog audit = captured.getValue();
        assertEquals("FAILED", audit.getOutcomeState());
        assertEquals(0, audit.getOperationStatus());
        assertEquals("QA-R01-failure-message", audit.getRequestId());
        assertEquals("CannotAcquireLockException", audit.getErrorMessage());
        assertNull(audit.getRequestParams());
        assertNull(audit.getResponseResult());
        verify(profiles, never()).touch(any(), any(), any(), any());
    }

    @Test
    void failedAuditDoesNotEscapeAsyncListenerAndIsLogged() {
        doThrow(new IllegalArgumentException("private-content"))
                .when(profiles).touchFromMessage(anyLong(), anyString(), anyString(), anyLong());
        doThrow(new IllegalStateException("database unavailable")).when(audits).logRequired(any());
        assertDoesNotThrow(() -> listener.handleChatMessageReceived(event("buyer")));
        verify(audits).logRequired(any());
    }

    @Test
    void sellerReplayDoesNotTouchBuyerProjection() {
        listener.handleChatMessageReceived(event("seller"));
        verifyNoInteractions(profiles, audits);
    }
}
