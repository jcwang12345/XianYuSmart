package com.xianyusmart.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家 360 写操作的持久化幂等记录。
 */
@Data
public class XianyuBuyerProfileRequest {

    private Long id;
    private Long tenantId;
    private Long xianyuAccountId;
    private String buyerUserId;
    private String requestId;
    private String idempotencyKey;
    private String requestFingerprint;
    private String responseJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
