package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xianyusmart.persistence.SensitiveStringTypeHandler;
import lombok.Data;



/**
 * 闲鱼Cookie实体类
 */
@Data
@TableName(value = "xianyu_cookie", autoResultMap = true)
public class XianyuCookie {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 关联的闲鱼账号ID
     */
    private Long xianyuAccountId;
    
    /**
     * 完整的Cookie字符串
     */
    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String cookieText;
    
    /**
     * _m_h5_tk token（用于API签名）
     */
    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String mH5Tk;
    
    /**
     * Cookie状态 1:有效 2:过期 3:失效
     */
    private Integer cookieStatus;
    
    /**
     * 过期时间（SQLite存储为TEXT）
     */
    private String expireTime;
    
    /**
     * 创建时间（SQLite存储为TEXT）
     */
    private String createdTime;
    
    /**
     * 更新时间（SQLite存储为TEXT）
     */
    private String updatedTime;
    
    /**
     * WebSocket accessToken
     */
    @JsonIgnore
    @TableField(typeHandler = SensitiveStringTypeHandler.class)
    private String websocketToken;
    
    /**
     * Token过期时间戳（毫秒）
     */
    private Long tokenExpireTime;

    @Version
    private Long credentialVersion;
}
