package com.xianyusmart.mapper;
import com.xianyusmart.entity.XianyuOrderConfirmation;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface OrderConfirmationMapper {
    @Insert("INSERT INTO xianyu_order_confirmation(tenant_id,xianyu_account_id,order_id) " +
            "VALUES(#{tenant},#{account},#{order}) ON DUPLICATE KEY UPDATE id=id")
    int enqueue(@Param("tenant") Long tenant, @Param("account") Long account, @Param("order") String order);

    @Select("SELECT c.* FROM xianyu_order_confirmation c JOIN xianyu_goods_order o " +
            "ON o.tenant_id=c.tenant_id AND o.xianyu_account_id=c.xianyu_account_id AND BINARY o.order_id=BINARY c.order_id " +
            "WHERE o.state=1 AND ((c.status IN ('PENDING','RETRY_WAIT') AND c.next_retry_time<=NOW(3)) " +
            "OR (c.status='PROCESSING' AND c.lease_expire_time<NOW(3))) ORDER BY c.next_retry_time LIMIT 20")
    List<XianyuOrderConfirmation> due();

    @Update("UPDATE xianyu_order_confirmation SET status='PROCESSING', lease_owner=#{token}, " +
            "lease_expire_time=DATE_ADD(NOW(3), INTERVAL 120 SECOND), attempt_count=attempt_count+1 WHERE id=#{id} " +
            "AND ((status IN ('PENDING','RETRY_WAIT') AND next_retry_time<=NOW(3)) OR (status='PROCESSING' AND lease_expire_time<NOW(3)))")
    int claim(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE xianyu_order_confirmation SET status=#{status}, last_error=#{error}, " +
            "next_retry_time=DATE_ADD(NOW(3),INTERVAL 5 MINUTE),lease_owner=NULL,lease_expire_time=NULL " +
            "WHERE id=#{id} AND status='PROCESSING' AND lease_owner=#{token} AND lease_expire_time>NOW(3)")
    int finish(@Param("id") Long id,@Param("token") String token,@Param("status") String status,@Param("error") String error);

    @Select("SELECT * FROM xianyu_order_confirmation WHERE xianyu_account_id=#{account} AND order_id=#{order}")
    XianyuOrderConfirmation find(@Param("account") Long account,@Param("order") String order);

    @Update("UPDATE xianyu_order_confirmation SET status='PENDING',attempt_count=0,next_retry_time=NOW(3),last_error=NULL " +
            "WHERE xianyu_account_id=#{account} AND order_id=#{order} AND status IN ('FAILED','REVIEW_REQUIRED','RETRY_WAIT')")
    int retry(@Param("account") Long account,@Param("order") String order);
}
