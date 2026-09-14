package com.xianyusmart.service.reply;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoReplyEscalationPolicyTest {

    private final AutoReplyEscalationPolicy policy = new AutoReplyEscalationPolicy();

    @Test
    void explicitHumanRequestAlwaysEscalatesBeforeAi() {
        AutoReplyEscalationPolicy.Decision decision = policy.evaluate(List.of(message("麻烦转人工客服，谢谢")));

        assertEquals("BUYER_REQUESTED_HUMAN", decision.reasonCode());
    }

    @Test
    void disputesAndCredentialRisksEscalate() {
        AutoReplyEscalationPolicy.Decision decision = policy.evaluate(List.of(message("我要申请退款，并且不要问我要验证码")));

        assertEquals("SENSITIVE_OR_HIGH_RISK", decision.reasonCode());
    }

    @Test
    void ordinaryProductQuestionCanContinueToReplyStrategies() {
        assertNull(policy.evaluate(List.of(message("这个插件支持 WPS 吗？有效期多久？"))));
        assertNull(policy.evaluate(List.of()));
    }

    private ChatMessageData message(String content) {
        ChatMessageData message = new ChatMessageData();
        message.setMsgContent(content);
        return message;
    }
}
