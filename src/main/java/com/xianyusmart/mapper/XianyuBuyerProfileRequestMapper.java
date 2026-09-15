package com.xianyusmart.mapper;

import com.xianyusmart.entity.XianyuBuyerProfileRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface XianyuBuyerProfileRequestMapper {

    @Insert("INSERT IGNORE INTO xianyu_buyer_profile_request " +
            "(tenant_id, xianyu_account_id, buyer_user_id, request_id, idempotency_key, request_fingerprint) " +
            "VALUES (#{tenantId}, #{accountId}, #{buyerUserId}, #{requestId}, #{idempotencyKey}, #{fingerprint})")
    int reserve(@Param("tenantId") Long tenantId,
                @Param("accountId") Long accountId,
                @Param("buyerUserId") String buyerUserId,
                @Param("requestId") String requestId,
                @Param("idempotencyKey") String idempotencyKey,
                @Param("fingerprint") String fingerprint);

    @Select("SELECT id, tenant_id, xianyu_account_id, buyer_user_id, request_id, idempotency_key, " +
            "request_fingerprint, response_json, create_time, update_time " +
            "FROM xianyu_buyer_profile_request WHERE tenant_id = #{tenantId} AND request_id = #{requestId} LIMIT 1")
    XianyuBuyerProfileRequest findByRequestId(@Param("tenantId") Long tenantId,
                                              @Param("requestId") String requestId);

    @Update("UPDATE xianyu_buyer_profile_request SET response_json = #{responseJson}, " +
            "update_time = CURRENT_TIMESTAMP(3) WHERE tenant_id = #{tenantId} AND request_id = #{requestId}")
    int complete(@Param("tenantId") Long tenantId,
                 @Param("requestId") String requestId,
                 @Param("responseJson") String responseJson);
}
