package com.xianyusmart.controller.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 首页统计信息响应DTO
 */
@Data
public class DashboardStatsRespDTO {
    
    /**
     * 账号总数
     */
    private Integer accountCount;
    
    /**
     * 商品总数
     */
    private Integer itemCount;
    
    /**
     * 在售商品数
     */
    private Integer sellingItemCount;

    /**
     * 审核中商品数
     */
    private Integer reviewingItemCount;
    
    /**
     * 已下架商品数
     */
    private Integer offShelfItemCount;
    
    /**
     * 已售出商品数
     */
    private Integer soldItemCount;

    /**
     * 已删除商品数
     */
    private Integer deletedItemCount;

    /**
     * 平台返回但系统尚未识别的商品状态数
     */
    private Integer unknownItemCount;

    private BigDecimal todayRevenue;

    private Integer todayDeliveryCount;

    private Integer todayReplyCount;

    private Integer pendingTaskCount;

    private Integer reviewRequiredCount;

    private Integer failedTaskCount;

    private Integer availableKamiCount;

    private Integer lowStockConfigCount;
}
