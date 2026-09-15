package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家关系资料
 */
@Data
@TableName("xianyu_buyer_profile")
public class XianyuBuyerProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private Long xianyuAccountId;

    private String buyerUserId;

    private String buyerUserName;

    private String tagsJson;

    private String note;

    private Integer automationBlocked;

    private String blockedReason;

    /** 独立于普通人工暂停，表示该买家已被加入客户黑名单。 */
    private Integer blacklisted;

    /** 最后一次变更黑名单的入口：BUYER_360 / MESSAGE_WORKSPACE。 */
    private String blacklistSource;

    private LocalDateTime blacklistUpdatedTime;

    private LocalDateTime lastInteractionTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
