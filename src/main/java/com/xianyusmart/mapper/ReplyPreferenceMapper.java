package com.xianyusmart.mapper;
import com.xianyusmart.entity.ReplyPreference;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ReplyPreferenceMapper {
    @Select("SELECT * FROM xianyu_reply_preference WHERE xianyu_account_id=#{account} AND xy_goods_id=#{goods}")
    ReplyPreference find(@Param("account") Long account,@Param("goods") String goods);
    @Insert("INSERT INTO xianyu_reply_preference(tenant_id,xianyu_account_id,xy_goods_id,welcome_enabled,welcome_text,welcome_image_url,bargain_floor) " +
            "VALUES(#{tenantId},#{xianyuAccountId},#{xyGoodsId},#{welcomeEnabled},#{welcomeText},#{welcomeImageUrl},#{bargainFloor}) " +
            "ON DUPLICATE KEY UPDATE welcome_enabled=VALUES(welcome_enabled),welcome_text=VALUES(welcome_text)," +
            "welcome_image_url=VALUES(welcome_image_url),bargain_floor=VALUES(bargain_floor)")
    int save(ReplyPreference preference);
    @Insert("INSERT IGNORE INTO xianyu_welcome_claim(tenant_id,xianyu_account_id,xy_goods_id,buyer_user_id,source_message_id,last_request_id) " +
            "VALUES(#{tenant},#{account},#{goods},#{buyer},#{messageId},#{messageId})")
    int claim(@Param("tenant") Long tenant,@Param("account") Long account,@Param("goods") String goods,
              @Param("buyer") String buyer,@Param("messageId") String messageId);
    @Update("UPDATE xianyu_welcome_claim SET status=#{status},last_request_id=#{requestId},resolution_note=#{note} " +
            "WHERE xianyu_account_id=#{account} AND xy_goods_id=#{goods} AND buyer_user_id=#{buyer} AND status='PROCESSING'")
    int finish(@Param("account") Long account,@Param("goods") String goods,@Param("buyer") String buyer,
               @Param("status") String status,@Param("requestId") String requestId,@Param("note") String note);
    @Delete("DELETE FROM xianyu_welcome_claim WHERE xianyu_account_id=#{account} AND xy_goods_id=#{goods} " +
            "AND buyer_user_id=#{buyer} AND status='PROCESSING'")
    int release(@Param("account") Long account,@Param("goods") String goods,@Param("buyer") String buyer);
}
