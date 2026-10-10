package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface XianyuGoodsAutoDeliveryConfigMapper extends BaseMapper<XianyuGoodsAutoDeliveryConfig> {

    @Select("SELECT id, xianyu_account_id, xianyu_goods_id, xy_goods_id, sku_id, " +
            "receipt_follow_up_messages, receipt_follow_up_interval_seconds " +
            "FROM xianyu_goods_auto_delivery_config " +
            "WHERE tenant_id=#{tenantId} AND xianyu_account_id=#{accountId} AND xy_goods_id=#{goodsId} " +
            "AND sku_key=COALESCE(#{skuId}, '') FOR UPDATE")
    XianyuGoodsAutoDeliveryConfig findOwnedReceiptFollowUpConfig(@Param("tenantId") Long tenantId,
                                                               @Param("accountId") Long accountId,
                                                               @Param("goodsId") String goodsId,
                                                               @Param("skuId") String skuId);

    @Update("UPDATE xianyu_goods_auto_delivery_config " +
            "SET receipt_follow_up_messages=#{messages}, receipt_follow_up_interval_seconds=#{intervalSeconds} " +
            "WHERE tenant_id=#{tenantId} AND id=#{configId} AND xianyu_account_id=#{accountId} " +
            "AND xy_goods_id=#{goodsId} AND sku_key=COALESCE(#{skuId}, '')")
    int updateReceiptFollowUp(@Param("tenantId") Long tenantId,
                              @Param("configId") Long configId,
                              @Param("accountId") Long accountId,
                              @Param("goodsId") String goodsId,
                              @Param("skuId") String skuId,
                              @Param("messages") String messages,
                              @Param("intervalSeconds") int intervalSeconds);
    
    @Select("SELECT id, xianyu_account_id, xianyu_goods_id, xy_goods_id, delivery_mode, fixed_template_id, sku_id, sku_name, auto_delivery_content, kami_config_ids, kami_delivery_template, delivery_message_template, voucher_delivery_enabled, chat_delivery_enabled, receipt_follow_up_messages, receipt_follow_up_interval_seconds, auto_delivery_image_url, auto_confirm_shipment, rag_delay_seconds, " +
            "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') as create_time, " +
            "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') as update_time " +
            "FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} AND xy_goods_id = #{xyGoodsId} AND sku_id = #{skuId} " +
            "LIMIT 1")
    XianyuGoodsAutoDeliveryConfig findByAccountIdAndGoodsIdAndSkuId(@Param("xianyuAccountId") Long xianyuAccountId, 
                                                                     @Param("xyGoodsId") String xyGoodsId,
                                                                     @Param("skuId") String skuId);
    
    @Select("SELECT id, xianyu_account_id, xianyu_goods_id, xy_goods_id, delivery_mode, fixed_template_id, sku_id, sku_name, auto_delivery_content, kami_config_ids, kami_delivery_template, delivery_message_template, voucher_delivery_enabled, chat_delivery_enabled, receipt_follow_up_messages, receipt_follow_up_interval_seconds, auto_delivery_image_url, auto_confirm_shipment, rag_delay_seconds, " +
            "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') as create_time, " +
            "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') as update_time " +
            "FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} AND xy_goods_id = #{xyGoodsId} AND sku_id IS NULL " +
            "LIMIT 1")
    XianyuGoodsAutoDeliveryConfig findByAccountIdAndGoodsIdNoSku(@Param("xianyuAccountId") Long xianyuAccountId,
                                                                  @Param("xyGoodsId") String xyGoodsId);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} AND xy_goods_id = #{xyGoodsId} AND sku_id IS NOT NULL")
    int countSkuSpecificByAccountAndGoodsId(@Param("xianyuAccountId") Long xianyuAccountId,
                                            @Param("xyGoodsId") String xyGoodsId);
    
    @Select("SELECT id, xianyu_account_id, xianyu_goods_id, xy_goods_id, delivery_mode, fixed_template_id, sku_id, sku_name, auto_delivery_content, kami_config_ids, kami_delivery_template, delivery_message_template, voucher_delivery_enabled, chat_delivery_enabled, receipt_follow_up_messages, receipt_follow_up_interval_seconds, auto_delivery_image_url, auto_confirm_shipment, rag_delay_seconds, " +
            "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') as create_time, " +
            "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') as update_time " +
            "FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} AND xy_goods_id = #{xyGoodsId} " +
            "ORDER BY sku_id ASC")
    List<XianyuGoodsAutoDeliveryConfig> findByAccountIdAndGoodsId(@Param("xianyuAccountId") Long xianyuAccountId,
                                                                   @Param("xyGoodsId") String xyGoodsId);
    
    @Select("SELECT id, xianyu_account_id, xianyu_goods_id, xy_goods_id, delivery_mode, fixed_template_id, sku_id, sku_name, auto_delivery_content, kami_config_ids, kami_delivery_template, delivery_message_template, voucher_delivery_enabled, chat_delivery_enabled, receipt_follow_up_messages, receipt_follow_up_interval_seconds, auto_delivery_image_url, auto_confirm_shipment, rag_delay_seconds, " +
            "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') as create_time, " +
            "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') as update_time " +
            "FROM xianyu_goods_auto_delivery_config " +
            "WHERE xianyu_account_id = #{xianyuAccountId} " +
            "ORDER BY create_time DESC")
    List<XianyuGoodsAutoDeliveryConfig> findByAccountId(@Param("xianyuAccountId") Long xianyuAccountId);
    
    @Delete("DELETE FROM xianyu_goods_auto_delivery_config WHERE xianyu_account_id = #{xianyuAccountId}")
    int deleteByAccountId(@Param("xianyuAccountId") Long xianyuAccountId);
}
