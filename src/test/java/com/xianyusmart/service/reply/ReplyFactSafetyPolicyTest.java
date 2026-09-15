package com.xianyusmart.service.reply;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplyFactSafetyPolicyTest {
    private final ReplyFactSafetyPolicy policy=new ReplyFactSafetyPolicy();

    @Test void validityNeedsActiveKnowledge(){
        var blocked=policy.evaluate(List.of(message("购买后有效期几天")),false);
        assertFalse(blocked.allowed());assertEquals("AI_NO_SAFE_ANSWER",blocked.reasonCode());
        assertTrue(policy.evaluate(List.of(message("购买后有效期几天")),true).allowed());
    }
    @Test void installationQrPriceAndAfterSalesNeedActiveKnowledge(){
        for(String text:List.of("怎么安装WPS插件","售后群二维码在哪","最低价格多少","不能用怎么售后"))
            assertFalse(policy.evaluate(List.of(message(text)),false).allowed(),text);
    }
    @Test void generalGreetingCanContinueWithoutProductFacts(){
        assertTrue(policy.evaluate(List.of(message("你好，在吗")),false).allowed());
    }
    private ChatMessageData message(String text){ChatMessageData value=new ChatMessageData();value.setMsgContent(text);return value;}
}
