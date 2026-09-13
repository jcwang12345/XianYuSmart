-- SEC-02：短时 access token 与可撤销、可轮换的 refresh token 会话。
ALTER TABLE sys_login_token
    ADD COLUMN refresh_token_hash CHAR(64) NULL AFTER token,
    ADD COLUMN previous_refresh_token_hash CHAR(64) NULL AFTER refresh_token_hash,
    ADD COLUMN refresh_expire_time VARCHAR(30) NULL AFTER previous_refresh_token_hash,
    ADD COLUMN refresh_rotated_time VARCHAR(30) NULL AFTER refresh_expire_time,
    ADD UNIQUE KEY uk_login_refresh_token (refresh_token_hash),
    ADD KEY idx_login_previous_refresh (previous_refresh_token_hash);
