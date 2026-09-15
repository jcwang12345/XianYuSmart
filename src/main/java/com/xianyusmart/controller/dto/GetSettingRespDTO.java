package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 获取配置响应DTO
 * @date 2026/4/22
 */
@Data
public class GetSettingRespDTO {
    private String settingKey;
    private String settingValue;
    private String settingDesc;
    /** 密钥类设置只返回是否已配置，不返回原文或掩码。 */
    private Boolean configured;
    private String updatedTime;
}
