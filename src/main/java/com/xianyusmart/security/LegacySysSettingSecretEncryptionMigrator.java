package com.xianyusmart.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts legacy plaintext system-setting credentials without ever returning
 * their value through an API or writing them to logs.
 */
@Slf4j
@Component
public class LegacySysSettingSecretEncryptionMigrator {

    private static final List<String> SECRET_KEYS = List.of(
            "ai_api_key", "ai_embedding_api_key", "ai_image_api_key", "email_smtp_password");

    private final JdbcTemplate jdbcTemplate;

    public LegacySysSettingSecretEncryptionMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        List<SecretRow> legacy = jdbcTemplate.query("""
                SELECT id, setting_value
                FROM xianyu_sys_setting
                WHERE setting_key IN (?, ?, ?, ?)
                  AND setting_value IS NOT NULL
                  AND setting_value <> ''
                  AND setting_value NOT LIKE 'enc:v1:%'
                """, (rs, rowNum) -> new SecretRow(rs.getLong("id"), rs.getString("setting_value")),
                SECRET_KEYS.toArray());
        int migrated = 0;
        for (SecretRow row : legacy) {
            migrated += jdbcTemplate.update("""
                    UPDATE xianyu_sys_setting
                    SET setting_value = ?, updated_time = COALESCE(updated_time, NOW(3))
                    WHERE id = ? AND setting_value = ?
                    """, SensitiveDataCodec.encrypt(row.value()), row.id(), row.value());
        }
        if (migrated > 0) {
            log.info("已完成历史系统配置密钥加密迁移，记录数: {}", migrated);
        }
    }

    private record SecretRow(Long id, String value) { }
}
