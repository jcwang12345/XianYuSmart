package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DeliveryExecutionMapper {
    @Update("UPDATE xianyu_goods_order SET delivery_status='REVIEW_REQUIRED', state=-1, " +
            "last_error_code='DELIVERY_UNCERTAIN', last_error_message='外发开始后任务中断，请核对订单及聊天', " +
            "fail_reason='外发开始后任务中断，请核对订单及聊天', next_retry_time=NULL, " +
            "lease_owner=NULL, lease_expire_time=NULL, delivery_message_next_retry_time=NULL, " +
            "exception_revision=exception_revision+1 WHERE delivery_status='PROCESSING' " +
            "AND lease_expire_time < NOW(3) AND external_attempt_started=1")
    int recoverUncertain();

    @Select("SELECT COUNT(*) FROM xianyu_goods_order o JOIN xianyu_account a ON a.id=o.xianyu_account_id " +
            "WHERE o.id=#{id} AND o.lease_owner=#{token} AND o.lease_expire_time>NOW(3) " +
            "AND o.delivery_status='PROCESSING' AND a.status=1")
    int active(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE xianyu_goods_order SET lease_expire_time=DATE_ADD(NOW(3), INTERVAL 120 SECOND) " +
            "WHERE id=#{id} AND lease_owner=#{token} AND lease_expire_time>NOW(3) AND delivery_status='PROCESSING'")
    int renew(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE xianyu_goods_order SET external_attempt_started=1 WHERE id=#{id} AND lease_owner=#{token} " +
            "AND lease_expire_time>NOW(3) AND delivery_status='PROCESSING'")
    int begin(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE xianyu_goods_order SET state=#{state}, content=COALESCE(#{content},content), fail_reason=#{error}, " +
            "delivery_status=CASE WHEN #{status}='FAILED' AND attempt_count<3 THEN 'RETRY_WAIT' ELSE #{status} END, " +
            "last_error_message=#{error}, next_retry_time=CASE WHEN #{status}='FAILED' AND attempt_count<3 " +
            "THEN DATE_ADD(NOW(3), INTERVAL 60 SECOND) ELSE NULL END, " +
            "lease_owner=NULL, lease_expire_time=NULL, exception_revision=exception_revision+IF(#{state}=-1,1,0) " +
            "WHERE id=#{id} AND lease_owner=#{token} AND lease_expire_time>NOW(3)")
    int finish(@Param("id") Long id, @Param("token") String token, @Param("state") int state,
               @Param("status") String status, @Param("content") String content, @Param("error") String error);

    @Update("UPDATE xianyu_goods_order SET delivery_status='PROCESSING', lease_owner=#{token}, " +
            "lease_expire_time=DATE_ADD(NOW(3), INTERVAL 120 SECOND), external_attempt_started=0 " +
            "WHERE id=#{id} AND state<>1 AND delivery_status IN ('PENDING','FAILED','RETRY_WAIT') " +
            "AND (lease_owner IS NULL OR lease_expire_time<NOW(3))")
    int claimManual(@Param("id") Long id, @Param("token") String token);
}
