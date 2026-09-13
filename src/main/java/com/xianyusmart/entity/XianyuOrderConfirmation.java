package com.xianyusmart.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class XianyuOrderConfirmation {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonIgnore private Long tenantId;
    private Long xianyuAccountId;
    private String orderId;
    private String status;
    private Integer attemptCount;
    private String leaseOwner;
    private LocalDateTime nextRetryTime;
    private LocalDateTime leaseExpireTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
