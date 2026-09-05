package com.xianyusmart.security;

import com.xianyusmart.entity.XianyuCookie;
import com.xianyusmart.mapper.XianyuCookieMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One-time, idempotent conversion of pre-V18 plaintext credentials. */
@Slf4j
@Component
public class LegacyCredentialEncryptionMigrator {
    private final XianyuCookieMapper cookieMapper;

    public LegacyCredentialEncryptionMigrator(XianyuCookieMapper cookieMapper) {
        this.cookieMapper = cookieMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        int migrated = 0;
        for (Long id : cookieMapper.selectLegacyPlaintextCredentialIds()) {
            XianyuCookie credential = cookieMapper.selectById(id);
            if (credential != null && cookieMapper.updateById(credential) == 1) {
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("已完成历史账号凭证加密迁移，记录数: {}", migrated);
        }
    }
}
