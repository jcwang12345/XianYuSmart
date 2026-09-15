package com.xianyusmart.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二维码登录响应
 */
@Data
@NoArgsConstructor
public class QRLoginResponse {
    
    private boolean success;
    private String sessionId;
    private String qrCodeUrl;
    private String message;
    /** Authoritative local generation time in epoch milliseconds. */
    private Long generatedAt;
    /** Local session deadline, not a guarantee of platform validity. */
    private Long expiresAt;

    public QRLoginResponse(boolean success, String sessionId, String qrCodeUrl, String message,
                           Long generatedAt, Long expiresAt) {
        this.success = success;
        this.sessionId = sessionId;
        this.qrCodeUrl = qrCodeUrl;
        this.message = message;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
    }

    /** Compatibility constructor retained for notification tests and callers. */
    public QRLoginResponse(boolean success, String sessionId, String qrCodeUrl, String message, Long expiresAt) {
        this(success, sessionId, qrCodeUrl, message, null, expiresAt);
    }

    public QRLoginResponse(boolean success, String sessionId, String qrCodeUrl, String message) {
        this(success, sessionId, qrCodeUrl, message, null, null);
    }
    
    public QRLoginResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}
