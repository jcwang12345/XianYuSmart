package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 持久化通知发件箱任务。
 */
@Data
@TableName("xianyu_notification_outbox")
public class XianyuNotificationOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long channelId;

    private String eventType;

    private Long xianyuAccountId;

    private String dedupeKey;

    private String eventId;

    private String title;

    private String content;

    private String dataJson;

    private String status;

    private Integer attemptCount;

    private LocalDateTime nextRetryTime;

    private String leaseOwner;

    private LocalDateTime leaseExpireTime;

    private String lastErrorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
