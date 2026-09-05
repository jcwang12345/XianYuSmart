package com.xianyusmart.controller.dto;

import lombok.Data;

/** targetAccountId 为空表示新增/重新登录该扫码账号，否则仅允许刷新指定账号。 */
@Data
public class QRLoginRequest {
    private Long targetAccountId;
}
