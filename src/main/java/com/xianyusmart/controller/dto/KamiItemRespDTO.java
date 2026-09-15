package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KamiItemRespDTO {

    private Long id;

    private Long kamiConfigId;

    private String kamiContent;

    private Integer status;

    private String orderId;

    private Long reservedAccountId;

    private LocalDateTime reservationExpireTime;

    private Long sourceConfigVersion;

    private Long rowVersion;

    private LocalDateTime usedTime;

    private Integer sortOrder;

    private LocalDateTime createTime;
}
