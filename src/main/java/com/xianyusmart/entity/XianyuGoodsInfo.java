package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 闲鱼商品信息实体类
 */
@Data
@TableName("xianyu_goods")
public class XianyuGoodsInfo {
    
    /**
     * 主键ID（雪花ID）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    
    /**
     * 闲鱼商品ID
     */
    private String xyGoodId;

    private String outerId;
    
    /**
     * 商品标题
     */
    private String title;
    
    /**
     * 封面图片URL
     */
    private String coverPic;
    
    /**
     * 商品详情图片（JSON数组）
     */
    private String infoPic;
    
    /**
     * 商品详情信息（预留字段）
     */
    private String detailInfo;
    
    /**
     * 商品详情页URL
     */
    private String detailUrl;
    
    /**
     * 关联的闲鱼账号ID
     */
    private Long xianyuAccountId;

    private String productSource;

    private String publishChannel;

    private String itemType;

    private String categoryId;

    private String categoryName;

    private String businessMode;

    private String conditionCode;
    
    /**
     * 商品价格
     */
    private String soldPrice;

    private java.math.BigDecimal originalPrice;

    private java.math.BigDecimal shippingFee;

    private String shippingType;
    
    private Integer skuCount;

    private Integer stock;
    
    private Integer status;

    private String syncStatus;

    private String coverageStatus;

    private String platformUpdatedTime;

    private String lastSyncedTime;

    private String lastSyncRequestId;

    private String platformSnapshotHash;

    private String lastSyncErrorCode;

    private String lastSyncErrorMessage;
    
    /**
     * 创建时间（SQLite存储为TEXT）
     */
    private String createdTime;
    
    /**
     * 更新时间（SQLite存储为TEXT）
     */
    private String updatedTime;
}
