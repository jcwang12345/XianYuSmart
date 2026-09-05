package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xianyusmart.persistence.SensitiveStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 与单个闲鱼账号绑定的稳定桌面 Web 运行档案。 */
@Data
@TableName(value = "xianyu_device_profile", autoResultMap = true)
public class XianyuDeviceProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonIgnore
    private Long tenantId;
    private Long xianyuAccountId;
    private String profileKey;
    private String profileType;
    private String platform;
    private String locale;
    private String timezoneId;
    private Integer viewportWidth;
    private Integer viewportHeight;
    private BigDecimal deviceScaleFactor;
    private String colorScheme;
    private String browserVersion;
    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String browserStorageState;
    private LocalDateTime storageStateUpdatedTime;
    private Integer status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
