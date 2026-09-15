package com.xianyusmart.security;

import com.xianyusmart.entity.XianyuNotificationChannel;
import com.xianyusmart.mapper.XianyuNotificationChannelMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One-time, idempotent conversion of notification endpoints and provider secrets. */
@Slf4j
@Component
public class LegacyNotificationSecretEncryptionMigrator {
    private final XianyuNotificationChannelMapper channelMapper;

    public LegacyNotificationSecretEncryptionMigrator(XianyuNotificationChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        int migrated = 0;
        for (Long id : channelMapper.selectLegacyPlaintextSecretIds()) {
            XianyuNotificationChannel channel = channelMapper.selectById(id);
            if (channel != null && channelMapper.updateById(channel) == 1) migrated++;
        }
        if (migrated > 0) log.info("已完成历史通知渠道密钥加密迁移，记录数: {}", migrated);
    }
}
