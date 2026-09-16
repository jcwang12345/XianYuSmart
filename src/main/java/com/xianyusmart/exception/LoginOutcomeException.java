package com.xianyusmart.exception;

/**
 * 登录接口的受控非成功结果。机器码是稳定协议，消息仅用于安全、可执行的用户提示。
 */
public final class LoginOutcomeException extends RuntimeException {

    public enum ErrorCode {
        INVALID_CREDENTIALS,
        TOTP_REQUIRED,
        TOTP_INVALID,
        LOGIN_RATE_LIMITED,
        TOTP_RATE_LIMITED
    }

    private final int businessCode;
    private final ErrorCode errorCode;
    private final boolean recordIpFailure;

    private LoginOutcomeException(int businessCode, ErrorCode errorCode, String message,
                                  boolean recordIpFailure) {
        super(message);
        this.businessCode = businessCode;
        this.errorCode = errorCode;
        this.recordIpFailure = recordIpFailure;
    }

    public static LoginOutcomeException invalidCredentials() {
        return new LoginOutcomeException(401, ErrorCode.INVALID_CREDENTIALS,
                "用户名或密码错误", true);
    }

    public static LoginOutcomeException totpRequired() {
        return new LoginOutcomeException(428, ErrorCode.TOTP_REQUIRED,
                "请输入验证码或恢复码继续登录", false);
    }

    public static LoginOutcomeException totpInvalid() {
        return new LoginOutcomeException(400, ErrorCode.TOTP_INVALID,
                "验证码或恢复码不正确，请重试", false);
    }

    public static LoginOutcomeException totpRateLimited() {
        return new LoginOutcomeException(429, ErrorCode.TOTP_RATE_LIMITED,
                "验证码错误次数过多，请稍后重试", false);
    }

    public int getBusinessCode() {
        return businessCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean shouldRecordIpFailure() {
        return recordIpFailure;
    }
}
