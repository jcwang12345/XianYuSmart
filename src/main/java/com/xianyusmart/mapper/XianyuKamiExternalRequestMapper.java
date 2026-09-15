package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuKamiExternalRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface XianyuKamiExternalRequestMapper extends BaseMapper<XianyuKamiExternalRequest> {

    @Insert("""
            INSERT IGNORE INTO xianyu_kami_external_request
                (kami_config_id, xianyu_account_id, order_id, request_token, payload_fingerprint, quantity,
                 request_status, result_unknown, attempt_count, circuit_state_at_request, quota_used_after,
                 create_time, update_time)
            VALUES
                (#{request.kamiConfigId}, #{request.xianyuAccountId}, #{request.orderId},
                 #{request.requestToken}, #{request.payloadFingerprint}, #{request.quantity}, 'PROCESSING', 0, 1,
                 #{request.circuitStateAtRequest}, #{request.quotaUsedAfter}, NOW(3), NOW(3))
            """)
    int insertIfAbsent(@Param("request") XianyuKamiExternalRequest request);

    @Select("""
            SELECT *
            FROM xianyu_kami_external_request
            WHERE kami_config_id = #{kamiConfigId} AND order_id = #{orderId}
            LIMIT 1
            """)
    XianyuKamiExternalRequest findByOrder(@Param("kamiConfigId") Long kamiConfigId,
                                          @Param("orderId") String orderId);

    @Select("""
            SELECT COUNT(*)
            FROM xianyu_kami_external_request
            WHERE kami_config_id = #{kamiConfigId}
              AND request_status IN ('PROCESSING', 'REVIEW_REQUIRED')
            """)
    int countUnsettledByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = 'PROCESSING',
                result_unknown = 0,
                attempt_count = attempt_count + 1,
                exception_revision = exception_revision + 1,
                error_message = NULL,
                next_retry_time = NULL,
                resolution_decision = NULL,
                resolution_note = NULL,
                resolution_request_id = NULL,
                resolved_by = NULL,
                resolved_time = NULL,
                update_time = NOW(3)
            WHERE id = #{id}
              AND attempt_count < 3
              AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))
              AND (
                request_status IN ('FAILED', 'MANUAL_NOT_SUPPLIED')
                OR (request_status = 'PROCESSING' AND update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
              )
            """)
    int claimRetry(@Param("id") Long id);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = 'SUCCESS',
                result_unknown = 0,
                response_excerpt = #{responseExcerpt},
                error_message = NULL,
                next_retry_time = NULL,
                update_time = NOW(3)
            WHERE id = #{id} AND request_status = 'PROCESSING'
            """)
    int markSuccess(@Param("id") Long id, @Param("responseExcerpt") String responseExcerpt);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = #{status},
                result_unknown = #{resultUnknown},
                error_message = #{errorMessage},
                next_retry_time = #{nextRetryTime},
                exception_revision = exception_revision + 1,
                update_time = NOW(3)
            WHERE id = #{id} AND request_status = 'PROCESSING'
            """)
    int markFailure(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("resultUnknown") int resultUnknown,
                    @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE xianyu_kami_external_request
               SET circuit_state_at_request = #{circuitState}, quota_used_after = #{quotaUsedAfter}
             WHERE id = #{id} AND request_status = 'PROCESSING'
            """)
    int updateAdmission(@Param("id") Long id, @Param("circuitState") String circuitState,
                        @Param("quotaUsedAfter") int quotaUsedAfter);
}
