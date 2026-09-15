package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 买家资料保存参数
 */
@Data
public class BuyerProfileSaveReqDTO {

    @NotBlank(message = "保存买家资料需要requestId")
    @Size(max = 80, message = "requestId不能超过80个字符")
    private String requestId;

    @Size(max = 80, message = "idempotencyKey不能超过80个字符")
    private String idempotencyKey;

    private Long id;

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "买家用户ID不能为空")
    private String buyerUserId;

    private String buyerUserName;

    private List<String> tags;

    private String note;

    private Boolean automationBlocked;

    private String blockedReason;

    /** null 表示兼容旧客户端并保留现状。 */
    private Boolean blacklisted;
}
