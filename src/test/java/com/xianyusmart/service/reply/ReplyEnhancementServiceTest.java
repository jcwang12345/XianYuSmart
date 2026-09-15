package com.xianyusmart.service.reply;

import com.xianyusmart.entity.ReplyPreference;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.ReplyPreferenceMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class ReplyEnhancementServiceTest {

    @Test
    void welcomeClaimBindsSourceMessageAndOnlyFirstClaimProducesReply() {
        ReplyPreferenceMapper mapper = mock(ReplyPreferenceMapper.class);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        ReplyEnhancementService service = new ReplyEnhancementService(mapper, accounts);
        ReplyPreference preference = preference();
        ChatMessageData message = message();
        when(mapper.find(9L, "goods-1")).thenReturn(preference);
        when(mapper.claim(6L, 9L, "goods-1", "buyer-1", "message-1")).thenReturn(1, 0);

        assertNotNull(service.welcome(message));
        assertNull(service.welcome(message));
        verify(mapper, times(2)).claim(6L, 9L, "goods-1", "buyer-1", "message-1");
    }

    @Test
    void preSendFailureReleasesButUnknownDeliveryMovesClaimToReview() {
        ReplyPreferenceMapper mapper = mock(ReplyPreferenceMapper.class);
        ReplyEnhancementService service = new ReplyEnhancementService(mapper, mock(XianyuAccountMapper.class));
        ChatMessageData message = message();

        service.releaseWelcome(message);
        service.finishWelcome(message, false);

        verify(mapper).release(9L, "goods-1", "buyer-1");
        verify(mapper).finish(9L, "goods-1", "buyer-1", "REVIEW_REQUIRED", "message-1",
                "已开始外部发送但未取得完整成功证据，禁止自动重发");
    }

    private ReplyPreference preference() {
        ReplyPreference value = new ReplyPreference();
        value.setTenantId(6L); value.setXianyuAccountId(9L); value.setXyGoodsId("goods-1");
        value.setWelcomeEnabled(1); value.setWelcomeText("欢迎咨询");
        return value;
    }

    private ChatMessageData message() {
        ChatMessageData value = new ChatMessageData();
        value.setXianyuAccountId(9L); value.setXyGoodsId("goods-1"); value.setSId("session-1");
        value.setSenderUserId("buyer-1"); value.setPnmId("message-1");
        return value;
    }
}
