package com.xianyusmart.service.reply;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertNull(policy.evaluate(List.of(message("我想申请退款，怎么处理？"))));
        assertNull(policy.evaluate(List.of()));
    }

    private ChatMessageData message(String content) {
        ChatMessageData message = new ChatMessageData();
        message.setMsgContent(content);
        return message;
    }

    @Test
    void refundFallbackDoesNotBlockLaterQuestionsButSafetyAndSendUncertaintyRemainBlocking() {
        for (String reason : List.of("NO_REPLY_STRATEGY", "AI_NO_SAFE_ANSWER", "LOW_CONFIDENCE", "AI_UNAVAILABLE")) {
            assertTrue(policy.isMessageOnlyRefundFallback(List.of(message("申请退款怎么处理")), reason));
        }
        assertFalse(policy.isMessageOnlyRefundFallback(List.of(message("退款，转人工客服")), "AI_NO_SAFE_ANSWER"));
        assertFalse(policy.isMessageOnlyRefundFallback(List.of(message("退款并报警")), "AI_UNAVAILABLE"));
        assertFalse(policy.isMessageOnlyRefundFallback(List.of(message("退款")), "MESSAGE_OUTCOME_UNKNOWN"));
        assertFalse(policy.isMessageOnlyRefundFallback(List.of(message("如何安装")), "AI_NO_SAFE_ANSWER"));
    }
}
