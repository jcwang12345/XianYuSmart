package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 删除账号请求DTO
 */
@Data
public class DeleteAccountReqDTO {
    private Long accountId;       // 账号ID
    private String confirmationText; // 必须与账号 ID 的十进制文本完全一致
}
