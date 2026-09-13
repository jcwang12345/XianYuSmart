-- ACC-03 / SEC-01：成员授权可引用店铺分组；实际权限在请求开始时展开为账号集合。
CREATE TABLE sys_user_account_group_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_group_scope (user_id,group_id),
    KEY idx_group_scope_group (tenant_id,group_id,user_id),
    CONSTRAINT fk_group_scope_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_scope_group FOREIGN KEY (group_id) REFERENCES xianyu_account_group(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
