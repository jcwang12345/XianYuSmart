package com.xianyusmart.event.chatMessageEvent.lister;

import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.AutoReplyDelayService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class ChatMessageEventHumanInterventionListenerTest {
    @Test
    void replayedSellerMessageDoesNotRestartTakeover() {
        Fixture fixture = new Fixture();
        when(fixture.messages.findByPnmId(1L, "old-pnm")).thenReturn(new XianyuChatMessage());

        fixture.listener.handleChatMessageReceived(fixture.event("old-pnm"));

        verify(fixture.delay, never()).recordSellerManualReply(anyLong(), anyString(), anyString());
    }

    @Test
    void newSellerMessageStillStartsTakeover() {
        Fixture fixture = new Fixture();

        fixture.listener.handleChatMessageReceived(fixture.event("new-pnm"));

        verify(fixture.delay).recordSellerManualReply(1L, "goods-1", "session-1");
    }

    private static class Fixture {
        final ChatMessageEventHumanInterventionListener listener = new ChatMessageEventHumanInterventionListener();
        final AutoReplyDelayService delay = mock(AutoReplyDelayService.class);
        final XianyuChatMessageMapper messages = mock(XianyuChatMessageMapper.class);

        Fixture() {
            AccountService accounts = mock(AccountService.class);
            when(accounts.getXianyuUserId(1L)).thenReturn("seller-1");
            ReflectionTestUtils.setField(listener, "autoReplyDelayService", delay);
            ReflectionTestUtils.setField(listener, "accountService", accounts);
            ReflectionTestUtils.setField(listener, "chatMessageMapper", messages);
        }

        ChatMessageReceivedEvent event(String pnmId) {
            ChatMessageData data = new ChatMessageData();
            data.setXianyuAccountId(1L);
            data.setContentType(1);
            data.setPnmId(pnmId);
            data.setSenderUserId("seller-1");
            data.setXyGoodsId("goods-1");
            data.setSId("session-1");
            return new ChatMessageReceivedEvent(this, data);
        }
    }
}
