package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xianyusmart.persistence.SensitiveStringTypeHandler;
import lombok.Data;

/**
 * 系统配置实体类
 * @date 2026/4/22
 */
@Data
@TableName(value = "xianyu_sys_setting", autoResultMap = true)
public class XianyuSysSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    /** 配置键 */
    private String settingKey;

    /** 配置值 */
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String settingValue;

    /** 配置描述 */
    private String settingDesc;

    /** 创建时间 */
    private String createdTime;

    /** 更新时间 */
    private String updatedTime;
}
