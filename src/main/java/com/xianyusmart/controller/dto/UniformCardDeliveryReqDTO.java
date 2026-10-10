package com.xianyusmart.controller.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Explicit merchant declaration: every variant of this product delivers the same entitlement. */
@Data
public class UniformCardDeliveryReqDTO {
    @NotNull @Positive private Long xianyuAccountId;
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") private String xyGoodsId;
    @NotNull @Positive private Long configId;
    @NotNull private Boolean enabled;
    private Boolean confirmAllVariantsSameEntitlement;

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("通用卡密配置不支持额外字段");
    }
}
