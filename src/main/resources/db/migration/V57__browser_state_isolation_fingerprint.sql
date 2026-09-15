-- ACC-11: detect accidental reuse of one browser state by multiple Xianyu accounts.
-- Only the irreversible SHA-256 digest is retained for comparison; API responses never expose it.
ALTER TABLE xianyu_device_profile
    ADD COLUMN storage_state_fingerprint CHAR(64) NULL AFTER browser_storage_state,
    ADD KEY idx_device_profile_storage_fingerprint (storage_state_fingerprint);

UPDATE xianyu_device_profile
   SET storage_state_fingerprint = SHA2(browser_storage_state, 256)
 WHERE browser_storage_state IS NOT NULL
   AND browser_storage_state <> ''
   AND storage_state_fingerprint IS NULL;
