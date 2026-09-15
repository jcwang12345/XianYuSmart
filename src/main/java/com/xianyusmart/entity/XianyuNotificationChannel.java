package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xianyusmart.persistence.SensitiveStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户通知渠道
 */
@Data
@TableName(value = "xianyu_notification_channel", autoResultMap = true)
public class XianyuNotificationChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private String channelName;

    private String channelType;

    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String webhookUrl;

    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String signingSecret;

    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String configJson;

    private String messageTemplate;

    private String eventTypes;

    /** ALL / GROUPS / ACCOUNTS */
    private String scopeType;

    @JsonIgnore
    private String scopeIdsJson;

    private Integer enabled;

    private LocalDateTime lastSuccessTime;

    private String lastErrorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
