package com.xianyusmart.controller.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Narrow update contract: no delivery, inventory, channel or automation-switch fields are accepted. */
@Data
public class ReceiptFollowUpUpdateReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    @Positive(message = "闲鱼账号ID无效")
    private Long xianyuAccountId;

    @NotBlank(message = "闲鱼商品ID不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,40}", message = "闲鱼商品ID格式无效")
    private String xyGoodsId;

    @Size(max = 32, message = "商品规格ID不能超过32个字符")
    private String skuId;

    @Size(max = 6000, message = "收货后话术内容过长")
    private String receiptFollowUpMessages;

    @Min(value = 5, message = "收货后话术间隔应为5至600秒")
    @Max(value = 600, message = "收货后话术间隔应为5至600秒")
    private Integer receiptFollowUpIntervalSeconds;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("该接口仅接受账号、商品、规格和收货后话术字段");
    }
}
