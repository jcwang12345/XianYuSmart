-- W3-BUG-004：把会话黑名单提升为买家 360 的一等事实，并保留最后变更入口与时间。
ALTER TABLE xianyu_buyer_profile
    ADD COLUMN blacklisted TINYINT NOT NULL DEFAULT 0 AFTER blocked_reason,
    ADD COLUMN blacklist_source VARCHAR(32) NULL AFTER blacklisted,
    ADD COLUMN blacklist_updated_time DATETIME(3) NULL AFTER blacklist_source,
    ADD KEY idx_buyer_profile_blacklist (tenant_id, blacklisted, blacklist_updated_time);

UPDATE xianyu_buyer_profile profile
JOIN (
    SELECT tenant_id, xianyu_account_id, buyer_user_id, MAX(updated_time) AS blacklist_updated_time
      FROM conversation_assignment
     WHERE customer_blacklisted = 1
       AND buyer_user_id IS NOT NULL
       AND buyer_user_id <> ''
     GROUP BY tenant_id, xianyu_account_id, buyer_user_id
) source
  ON source.tenant_id = profile.tenant_id
 AND source.xianyu_account_id = profile.xianyu_account_id
 AND source.buyer_user_id = profile.buyer_user_id
SET profile.blacklisted = 1,
    profile.blacklist_source = 'MESSAGE_WORKSPACE',
    profile.blacklist_updated_time = source.blacklist_updated_time,
    profile.automation_blocked = 1,
    profile.blocked_reason = COALESCE(profile.blocked_reason, '[会话] 已加入客户黑名单');
