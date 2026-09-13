package com.xianyusmart.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 二维码登录会话
 */
@Data
public class QRLoginSession {
    
    private String sessionId;
    private String status; // waiting, scanned, success, expired, cancelled, verification_required
    private String qrCodeUrl;
    private String qrContent;
    private Map<String, String> cookies = new HashMap<>();
    private String unb;
    private long createdTime;
    // Local monitoring ceiling only. The platform may invalidate the QR sooner.
    public static final long MAX_WAIT_MS = 15 * 60 * 1000L;
    private long expireTime = MAX_WAIT_MS;
    private Map<String, String> params = new HashMap<>();
    private String verificationUrl;
    @JsonIgnore
    private Long tenantId;
    private Long accountId;
    private Long targetAccountId;
    @JsonIgnore
    private String errorMessage;
    
    public QRLoginSession(String sessionId) {
        this.sessionId = sessionId;
        this.status = "waiting";
        this.createdTime = System.currentTimeMillis();
    }
    
    /**
     * 检查是否过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdTime > expireTime;
    }
}
