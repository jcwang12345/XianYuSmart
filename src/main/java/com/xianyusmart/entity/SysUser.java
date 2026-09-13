package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xianyusmart.persistence.SensitiveStringTypeHandler;
import lombok.Data;

/**
 * 系统用户实体类
 * @date 2026/4/22
 */
@Data
@TableName(value = "sys_user", autoResultMap = true)
public class SysUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属经营租户；多个团队成员可共享同一租户。 */
    private Long tenantId;

    /** 用户名 */
    private String username;

    /** 密码（BCrypt加密） */
    private String password;

    /** 平台角色：ADMIN 管理员，USER 普通租户 */
    private String role;

    /** 租户内角色：OWNER/TENANT_ADMIN/OPERATOR/SUPPORT/FINANCE。 */
    private String memberRole;

    /** 闲鱼账号访问范围：ALL/SELECTED。 */
    private String accountScopeMode;

    private Integer totpEnabled;

    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String totpSecret;

    @JsonIgnore
    private String totpRecoveryCodes;

    /** 状态 1:正常 0:禁用 */
    private Integer status;

    /** 最后登录时间 */
    private String lastLoginTime;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 创建时间 */
    private String createdTime;

    /** 更新时间 */
    private String updatedTime;
}
