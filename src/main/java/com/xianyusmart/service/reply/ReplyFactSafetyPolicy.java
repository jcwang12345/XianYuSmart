package com.xianyusmart.service.reply;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 对需要精确事实依据的问题执行模型调用前门禁。 */
@Component
public class ReplyFactSafetyPolicy {
    private static final Pattern FACTUAL = Pattern.compile(
            "有效期|几天|多久到期|激活|二维码|扫码|加群|售后群|安装|教程|office|wps|插件|退款|退货|售后|不能用|价格|多少钱|优惠|便宜|砍价",
            Pattern.CASE_INSENSITIVE);

    public Decision evaluate(List<com.xianyusmart.event.chatMessageEvent.ChatMessageData> messages,
                             boolean hasActiveKnowledge) {
        String text = messages == null ? "" : messages.stream()
                .map(com.xianyusmart.event.chatMessageEvent.ChatMessageData::getMsgContent)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left,right)->left+"\n"+right).orElse("").toLowerCase(Locale.ROOT);
        if (!hasActiveKnowledge && FACTUAL.matcher(text).find()) {
            return new Decision(false,"AI_NO_SAFE_ANSWER","事实型问题没有处于有效期内的商品知识版本，禁止模型猜测");
        }
        return new Decision(true,null,null);
    }
    public record Decision(boolean allowed,String reasonCode,String reasonDetail){}
}
