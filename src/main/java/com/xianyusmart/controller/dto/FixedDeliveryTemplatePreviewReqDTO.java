package com.xianyusmart.controller.dto;

import lombok.Data;

@Data
public class FixedDeliveryTemplatePreviewReqDTO {
    private Long xianyuAccountId;
    private Long templateId;
    private String buyerName;
    private String orderId;
    private String deliveryContent;
    private String messageTemplate;
}
