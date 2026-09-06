-- Preserve account identity, cookies and storage; migrate legacy Linux profiles once.
-- Use the same stable assignment as AccountBrowserProfileService.platformForAccount.
UPDATE xianyu_device_profile
SET platform = CASE WHEN MOD(xianyu_account_id, 2) = 0 THEN 'MACOS' ELSE 'WINDOWS' END,
    viewport_width = CASE MOD(xianyu_account_id - 1, 4)
        WHEN 0 THEN 1365
        WHEN 1 THEN 1440
        WHEN 2 THEN 1536
        ELSE 1600
    END,
    viewport_height = CASE MOD(xianyu_account_id - 1, 4)
        WHEN 0 THEN 768
        WHEN 1 THEN 900
        WHEN 2 THEN 864
        ELSE 900
    END
WHERE platform = 'LINUX';
