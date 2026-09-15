package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.controller.dto.BuyerProfileRespDTO;
import com.xianyusmart.controller.dto.BuyerRelatedGoodsDTO;
import com.xianyusmart.entity.XianyuBuyerProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家关系资料 Mapper
 */
@Mapper
public interface XianyuBuyerProfileMapper extends BaseMapper<XianyuBuyerProfile> {

    @Select("SELECT * FROM xianyu_buyer_profile WHERE xianyu_account_id = #{accountId} " +
            "AND buyer_user_id = #{buyerUserId} LIMIT 1")
    XianyuBuyerProfile findByBuyer(@Param("accountId") Long accountId,
                                   @Param("buyerUserId") String buyerUserId);

    @Insert("INSERT INTO xianyu_buyer_profile (tenant_id, xianyu_account_id, buyer_user_id, " +
            "buyer_user_name, last_interaction_time) VALUES (#{tenantId}, #{accountId}, #{buyerUserId}, " +
            "#{buyerUserName}, #{interactionTime}) ON DUPLICATE KEY UPDATE " +
            "buyer_user_name = COALESCE(NULLIF(VALUES(buyer_user_name), ''), buyer_user_name), " +
            "last_interaction_time = GREATEST(COALESCE(last_interaction_time, VALUES(last_interaction_time)), " +
            "VALUES(last_interaction_time))")
    int touch(@Param("tenantId") Long tenantId,
              @Param("accountId") Long accountId,
              @Param("buyerUserId") String buyerUserId,
              @Param("buyerUserName") String buyerUserName,
              @Param("interactionTime") LocalDateTime interactionTime);

    @Select("<script>" +
            "SELECT profile.*, " +
            "(SELECT COUNT(*) FROM xianyu_chat_message message WHERE message.xianyu_account_id = profile.xianyu_account_id " +
            "AND (message.sender_user_id = profile.buyer_user_id OR message.s_id IN " +
            "(SELECT orders.sid FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = profile.xianyu_account_id " +
            "AND orders.buyer_user_id = profile.buyer_user_id AND orders.sid IS NOT NULL))) AS message_count, " +
            "(SELECT COUNT(*) FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = profile.xianyu_account_id " +
            "AND orders.buyer_user_id = profile.buyer_user_id) AS order_count, " +
            "(SELECT SUM(orders.order_amount) FROM xianyu_goods_order orders " +
            "WHERE orders.xianyu_account_id = profile.xianyu_account_id AND orders.buyer_user_id = profile.buyer_user_id " +
            "AND orders.state = 1) AS total_amount, " +
            "(SELECT COUNT(orders.order_amount) FROM xianyu_goods_order orders " +
            "WHERE orders.xianyu_account_id = profile.xianyu_account_id AND orders.buyer_user_id = profile.buyer_user_id " +
            "AND orders.state = 1) AS amount_known_order_count " +
            "FROM xianyu_buyer_profile profile WHERE 1 = 1 " +
            "<if test='accountId != null'>AND profile.xianyu_account_id = #{accountId} </if>" +
            "<if test='blocked != null'>AND profile.automation_blocked = #{blocked} </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (profile.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.buyer_user_id LIKE CONCAT('%', #{keyword}, '%') OR profile.tags_json LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.note LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "ORDER BY profile.automation_blocked DESC, profile.last_interaction_time DESC, profile.id DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<BuyerProfileRespDTO> selectPage(@Param("accountId") Long accountId,
                                         @Param("keyword") String keyword,
                                         @Param("blocked") Integer blocked,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_buyer_profile profile WHERE 1 = 1 " +
            "<if test='accountId != null'>AND profile.xianyu_account_id = #{accountId} </if>" +
            "<if test='blocked != null'>AND profile.automation_blocked = #{blocked} </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (profile.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.buyer_user_id LIKE CONCAT('%', #{keyword}, '%') OR profile.tags_json LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.note LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "</script>")
    long countPage(@Param("accountId") Long accountId,
                   @Param("keyword") String keyword,
                   @Param("blocked") Integer blocked);

    @Select("SELECT profile.*, " +
            "(SELECT COUNT(*) FROM xianyu_chat_message message WHERE message.xianyu_account_id = profile.xianyu_account_id " +
            "AND (message.sender_user_id = profile.buyer_user_id OR message.s_id IN " +
            "(SELECT orders.sid FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = profile.xianyu_account_id " +
            "AND orders.buyer_user_id = profile.buyer_user_id AND orders.sid IS NOT NULL))) AS message_count, " +
            "(SELECT COUNT(*) FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = profile.xianyu_account_id " +
            "AND orders.buyer_user_id = profile.buyer_user_id) AS order_count, " +
            "(SELECT SUM(orders.order_amount) FROM xianyu_goods_order orders " +
            "WHERE orders.xianyu_account_id = profile.xianyu_account_id AND orders.buyer_user_id = profile.buyer_user_id " +
            "AND orders.state = 1) AS total_amount, " +
            "(SELECT COUNT(orders.order_amount) FROM xianyu_goods_order orders " +
            "WHERE orders.xianyu_account_id = profile.xianyu_account_id AND orders.buyer_user_id = profile.buyer_user_id " +
            "AND orders.state = 1) AS amount_known_order_count " +
            "FROM xianyu_buyer_profile profile WHERE profile.xianyu_account_id = #{accountId} " +
            "AND profile.buyer_user_id = #{buyerUserId} LIMIT 1")
    BuyerProfileRespDTO selectDetail(@Param("accountId") Long accountId,
                                     @Param("buyerUserId") String buyerUserId);

    @Select("SELECT orders.xy_goods_id, COALESCE(MAX(goods.title), MAX(orders.goods_title)) AS title, " +
            "MAX(goods.cover_pic) AS cover_pic, MAX(goods.sold_price) AS sold_price, COUNT(*) AS order_count, " +
            "SUM(CASE WHEN orders.state = 1 THEN orders.order_amount END) AS total_amount, " +
            "COUNT(CASE WHEN orders.state = 1 THEN orders.order_amount END) AS amount_known_order_count, " +
            "MAX(orders.create_time) AS last_order_time FROM xianyu_goods_order orders " +
            "LEFT JOIN xianyu_goods goods ON goods.xianyu_account_id = orders.xianyu_account_id " +
            "AND goods.xy_good_id = orders.xy_goods_id WHERE orders.xianyu_account_id = #{accountId} " +
            "AND orders.buyer_user_id = #{buyerUserId} GROUP BY orders.xy_goods_id " +
            "ORDER BY last_order_time DESC")
    List<BuyerRelatedGoodsDTO> selectRelatedGoods(@Param("accountId") Long accountId,
                                                  @Param("buyerUserId") String buyerUserId);

    @Update("UPDATE conversation_assignment SET customer_blacklisted = #{blacklisted} " +
            "WHERE tenant_id = #{tenantId} AND xianyu_account_id = #{accountId} " +
            "AND buyer_user_id = #{buyerUserId}")
    int updateConversationBlacklist(@Param("tenantId") Long tenantId,
                                    @Param("accountId") Long accountId,
                                    @Param("buyerUserId") String buyerUserId,
                                    @Param("blacklisted") int blacklisted);

    /** 显式写入可空原因，避免 MyBatis-Plus 的 null 忽略策略留下陈旧黑名单文案。 */
    @Update("UPDATE xianyu_buyer_profile SET automation_blocked = #{automationBlocked}, " +
            "blocked_reason = #{blockedReason}, blacklisted = #{blacklisted}, " +
            "blacklist_source = #{blacklistSource}, blacklist_updated_time = #{blacklistUpdatedTime} " +
            "WHERE tenant_id = #{tenantId} AND xianyu_account_id = #{accountId} " +
            "AND buyer_user_id = #{buyerUserId}")
    int updateAutomationAndBlacklist(@Param("tenantId") Long tenantId,
                                     @Param("accountId") Long accountId,
                                     @Param("buyerUserId") String buyerUserId,
                                     @Param("automationBlocked") int automationBlocked,
                                     @Param("blockedReason") String blockedReason,
                                     @Param("blacklisted") int blacklisted,
                                     @Param("blacklistSource") String blacklistSource,
                                     @Param("blacklistUpdatedTime") LocalDateTime blacklistUpdatedTime);
}
