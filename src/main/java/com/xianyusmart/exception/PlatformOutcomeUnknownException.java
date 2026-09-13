package com.xianyusmart.exception;

/** 平台写请求可能已到达，但客户端没有拿到可确认响应。 */
public class PlatformOutcomeUnknownException extends RuntimeException {
    public PlatformOutcomeUnknownException(String message) {
        super(message);
    }
}
