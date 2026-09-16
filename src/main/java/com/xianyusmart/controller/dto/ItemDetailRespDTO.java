package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 获取商品详情响应DTO
 * 仅包含itemWithConfig字段，不再包含旧版的item字段
 */
@Data
public class ItemDetailRespDTO {
    
    /**
     * 商品信息（包含配置信息）
     */
    private ItemWithConfigDTO itemWithConfig;

    /** 本次请求是否完成了经验证的平台快照刷新。 */
    private Boolean refreshed;

    /** CACHE / SUCCESS / VERIFICATION_REQUIRED / BUSY / FORBIDDEN / NOT_FOUND / UNAVAILABLE。 */
    private String refreshStatus;

    /** 可直接展示且不含凭证的刷新说明。 */
    private String refreshMessage;
}
