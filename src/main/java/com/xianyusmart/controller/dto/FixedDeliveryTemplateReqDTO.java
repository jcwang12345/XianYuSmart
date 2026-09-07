package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 固定内容发货模板请求
 */
@Data
public class FixedDeliveryTemplateReqDTO {

    private Long id;

    private Long xianyuAccountId;

    private List<Long> xianyuAccountIds;

    private String templateName;

    private String deliveryContent;

    private String messageTemplate;
}
