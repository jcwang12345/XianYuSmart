package com.xianyusmart.service.reply;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 发送前的确定性转人工门禁；规则只决定是否拦截，不生成面向买家的文本。 */
@Component
public class AutoReplyEscalationPolicy {

    private static final Pattern HUMAN_REQUEST = Pattern.compile(
            "转(?:接)?人工(?:客服)?|真人(?:客服)?|人工客服|找客服|客服介入|找个人(?:客服)?");
    private static final Pattern HIGH_RISK = Pattern.compile(
            "退款|退货|投诉|举报|报警|律师|起诉|赔偿|仲裁|小法庭|账号被盗|验证码|密码|银行卡|线下交易");

    public Decision evaluate(List<ChatMessageData> messages) {
        if (messages == null || messages.isEmpty()) return null;
        String text = messages.stream().map(ChatMessageData::getMsgContent)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("").toLowerCase(Locale.ROOT);
        if (HUMAN_REQUEST.matcher(text).find()) {
            return new Decision("BUYER_REQUESTED_HUMAN", "买家明确要求人工处理");
        }
        if (HIGH_RISK.matcher(text).find()) {
            return new Decision("SENSITIVE_OR_HIGH_RISK", "消息涉及退款、争议、账号安全或平台外交易风险");
        }
        return null;
    }

    public record Decision(String reasonCode, String reasonDetail) {}
}
