package com.xianyusmart.event;

/** 用户明确登录系统或完成闲鱼扫码后，触发一次有界的账号上线与只读刷新。 */
public record AccountAuthenticatedEvent(Long accountId, String triggerType) {
}
