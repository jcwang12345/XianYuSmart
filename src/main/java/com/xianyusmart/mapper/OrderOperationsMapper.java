package com.xianyusmart.mapper;
import com.xianyusmart.entity.XianyuGoodsOrder;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface OrderOperationsMapper {
    @Select("SELECT * FROM xianyu_goods_order WHERE xianyu_account_id=#{account} " +
            "AND (platform_trade_status IS NULL OR platform_trade_status NOT IN ('CLOSED','REFUNDED','COMPLETED')) " +
            "AND (platform_status_checked_at IS NULL OR platform_status_checked_at<DATE_SUB(NOW(3),INTERVAL 5 MINUTE)) " +
            "ORDER BY platform_status_checked_at,id DESC LIMIT 10")
    List<XianyuGoodsOrder> due(@Param("account") Long account);
    @Update("UPDATE xianyu_goods_order SET platform_status_checked_at=NOW(3),platform_trade_status=COALESCE(#{status},platform_trade_status)," +
            "confirm_state=IF(#{status} IN ('SHIPPED','COMPLETED'),1,confirm_state)," +
            "buyer_confirmed_receipt=IF(#{status}='COMPLETED',1,buyer_confirmed_receipt),sku_id=COALESCE(#{sku},sku_id)," +
            "buyer_user_id=COALESCE(NULLIF(buyer_user_id,''),#{buyer}) WHERE id=#{id}")
    int reconcile(@Param("id") Long id,@Param("status") String status,@Param("sku") String sku,@Param("buyer") String buyer);
}
