package com.xianyusmart.service.reply;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 最后一道发送前防线，避免把内部错误或明显的平台外引导发给买家。 */
@Component
public class AIReplySafetyGuard {

    private static final int MAX_REPLY_CHARS = 600;
    private static final List<String> INTERNAL_ERROR_MARKERS = List.of(
            "ai服务暂未配置", "ai回复生成失败", "【ai服务错误】", "api key未配置");
    private static final List<String> OFF_PLATFORM_MARKERS = List.of(
            "加微信", "微信联系", "加qq", "qq联系", "银行卡转账", "线下交易", "脱离平台");

    public String safeOrNull(String reply) {
        if (reply == null || reply.isBlank()) return null;
        String normalized = reply.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (INTERNAL_ERROR_MARKERS.stream().anyMatch(lower::contains)) return null;
        if (OFF_PLATFORM_MARKERS.stream().anyMatch(lower::contains)) {
            return "为保障双方权益，请直接通过闲鱼平台沟通和交易。";
        }
        normalized = normalized.replaceFirst("^(assistant|助手|客服)\\s*[:：]\\s*", "").trim();
        if (normalized.length() > MAX_REPLY_CHARS) {
            normalized = normalized.substring(0, MAX_REPLY_CHARS).trim() + "…";
        }
        return normalized.isBlank() ? null : normalized;
    }
}
