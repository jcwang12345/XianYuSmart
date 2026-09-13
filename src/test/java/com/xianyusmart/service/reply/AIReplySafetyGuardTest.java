package com.xianyusmart.service.reply;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AIReplySafetyGuardTest {

    private final AIReplySafetyGuard guard = new AIReplySafetyGuard();

    @Test
    void rejectsInternalServiceErrors() {
        assertNull(guard.safeOrNull("【AI服务错误】模型请求超时"));
        assertNull(guard.safeOrNull("AI服务暂未配置，请配置API Key"));
    }

    @Test
    void replacesOffPlatformGuidance() {
        assertEquals("为保障双方权益，请直接通过闲鱼平台沟通和交易。",
                guard.safeOrNull("请加微信联系我交易"));
    }

    @Test
    void removesAssistantPrefix() {
        assertEquals("您好，商品还在。", guard.safeOrNull("助手：您好，商品还在。"));
    }
}
