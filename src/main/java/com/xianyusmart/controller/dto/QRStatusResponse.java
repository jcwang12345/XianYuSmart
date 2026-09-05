package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 二维码状态响应
 */
@Data
public class QRStatusResponse {
    
    private String status;
    private String sessionId;
    private Long accountId;
    private String verificationUrl;
    private String message;
}
