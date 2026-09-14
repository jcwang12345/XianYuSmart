package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.MerchantTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商家运营任务Mapper
 */
@Mapper
public interface MerchantTaskMapper extends BaseMapper<MerchantTask> {

    @Select("<script>SELECT * FROM merchant_task WHERE 1=1 " +
            "<if test='taskId != null'>AND id = #{taskId}</if> " +
            "<if test='requestId != null and requestId != \"\"'>AND request_key = #{requestId}</if> " +
            "<if test='accountId != null'>AND xianyu_account_id = #{accountId}</if> " +
            "<if test='taskType != null and taskType != \"\"'>AND task_type = #{taskType}</if> " +
            "<if test='status != null'>AND status = #{status}</if> ORDER BY created_time DESC LIMIT #{limit}</script>")
    List<MerchantTask> selectRecent(@Param("taskId") Long taskId,
                                    @Param("requestId") String requestId,
                                    @Param("accountId") Long accountId,
                                    @Param("taskType") String taskType,
                                    @Param("status") Integer status,
                                    @Param("limit") int limit);

    @Select("SELECT * FROM merchant_task WHERE tenant_id = #{tenantId} AND task_type = #{taskType} " +
            "AND request_key = #{requestKey} LIMIT 1")
    MerchantTask selectByRequestKey(@Param("tenantId") Long tenantId,
                                    @Param("taskType") String taskType,
                                    @Param("requestKey") String requestKey);

    // 直接返回任务汇总，概览页无需加载任务明细再计算。
    @Select("SELECT COUNT(*) AS taskCount, COALESCE(SUM(CASE WHEN status = -1 THEN 1 ELSE 0 END), 0) AS failedTaskCount FROM merchant_task")
    Map<String, Object> selectOverviewCounts();

    @Select("SELECT * FROM merchant_task WHERE ((status = 0 AND scheduled_time <= NOW(3)) " +
            "OR (status = -1 AND attempt_count < max_attempts AND next_retry_time <= NOW(3)) " +
            "OR (status = 1 AND attempt_count < max_attempts AND updated_time <= DATE_SUB(NOW(3), INTERVAL 10 MINUTE))) " +
            "ORDER BY scheduled_time, id LIMIT #{limit}")
    List<MerchantTask> selectDue(@Param("limit") int limit);

    @Update("UPDATE merchant_task SET status = 1, attempt_count = attempt_count + 1 " +
            "WHERE id = #{id} AND (status IN (0, -1) OR (status = 1 AND updated_time <= DATE_SUB(NOW(3), INTERVAL 10 MINUTE)))")
    int claim(@Param("id") Long id);

    @Update("UPDATE merchant_task SET status = 2, result_json = #{resultJson}, error_message = NULL, next_retry_time = NULL, " +
            "verification_status = CASE WHEN task_type <> 'PUBLISH' THEN 'NOT_REQUIRED' " +
            "WHEN JSON_VALID(#{resultJson}) AND JSON_EXTRACT(#{resultJson}, '$.localSynced') = false THEN 'LOCAL_PENDING' " +
            "WHEN JSON_VALID(#{resultJson}) AND NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{resultJson}, '$.itemId')), '') IS NOT NULL " +
            "THEN 'VERIFIED' ELSE 'PENDING' END, " +
            "outcome_state = CASE WHEN task_type <> 'PUBLISH' THEN 'LOCAL_SUCCESS' " +
            "WHEN JSON_VALID(#{resultJson}) AND JSON_UNQUOTE(JSON_EXTRACT(#{resultJson}, '$.executionChannel')) = 'QA_MOCK' " +
            "THEN COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(#{resultJson}, '$.outcomeState')), ''), 'QA_CONFIRMED') " +
            "WHEN JSON_VALID(#{resultJson}) AND JSON_EXTRACT(#{resultJson}, '$.localSynced') = false " +
            "THEN 'PLATFORM_CONFIRMED_LOCAL_PENDING' ELSE 'PLATFORM_CONFIRMED' END, " +
            "data_source = CASE WHEN task_type='PUBLISH' AND JSON_VALID(#{resultJson}) " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(#{resultJson}, '$.executionChannel')) = 'QA_MOCK' THEN 'QA_FIXTURE' " +
            "WHEN task_type='PUBLISH' THEN 'PLATFORM_WEB' ELSE 'LOCAL' END, " +
            "recovery_hint = CASE WHEN task_type='PUBLISH' AND JSON_VALID(#{resultJson}) " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(#{resultJson}, '$.executionChannel')) = 'QA_MOCK' " +
            "AND JSON_EXTRACT(#{resultJson}, '$.localSynced') = false " +
            "THEN '隔离 QA 已确认执行但本地商品未落库；未调用闲鱼平台，可按任务结果修复夹具；禁止据此在真实通道重复发布' " +
            "WHEN task_type='PUBLISH' AND JSON_VALID(#{resultJson}) " +
            "AND JSON_EXTRACT(#{resultJson}, '$.localSynced') = false " +
            "THEN '平台已发布成功，请按商品ID修复本地缓存；不要重复发布' ELSE NULL END WHERE id = #{id}")
    int complete(@Param("id") Long id, @Param("resultJson") String resultJson);

    @Update("UPDATE merchant_task SET status = -1, outcome_state='FAILED', error_message = #{errorMessage}, " +
            "next_retry_time = #{nextRetryTime} WHERE id = #{id}")
    int fail(@Param("id") Long id, @Param("errorMessage") String errorMessage,
             @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("UPDATE merchant_task SET status = 4, verification_status='UNKNOWN', outcome_state='UNKNOWN', " +
            "data_source='PLATFORM_WEB', error_message=#{errorMessage}, next_retry_time=NULL, " +
            "recovery_hint='请按请求ID查询平台商品结果，确认前禁止重复发布' WHERE id=#{id}")
    int markOutcomeUnknown(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    @Update("UPDATE merchant_task SET status = 4, verification_status='UNKNOWN', outcome_state='UNKNOWN', " +
            "data_source='QA_FIXTURE', result_json=#{resultJson}, error_message=#{errorMessage}, next_retry_time=NULL, " +
            "recovery_hint='隔离 QA 结果未知；未调用闲鱼平台，可按请求ID检查状态；禁止据此在真实通道重复发布' WHERE id=#{id}")
    int markQaOutcomeUnknown(@Param("id") Long id,
                             @Param("resultJson") String resultJson,
                             @Param("errorMessage") String errorMessage);

    @Update("UPDATE merchant_task SET status = 0, attempt_count = 0, scheduled_time = NOW(3), next_retry_time = NULL, error_message = NULL WHERE id = #{id}")
    int requeue(@Param("id") Long id);

    /** 只允许取消尚未被执行器领取的任务，避免中断已在执行的平台操作。 */
    @Update("UPDATE merchant_task SET status = 3, next_retry_time = NULL, " +
            "error_message = '用户已取消，任务不会再执行' WHERE id = #{id} AND status IN (0, -1)")
    int cancel(@Param("id") Long id);

    @Update("UPDATE merchant_task SET status = 0, attempt_count = GREATEST(attempt_count - 1, 0), " +
            "scheduled_time = #{retryAt}, next_retry_time = NULL, error_message = #{message} WHERE id = #{id}")
    int defer(@Param("id") Long id, @Param("retryAt") LocalDateTime retryAt,
              @Param("message") String message);

    @Select("SELECT COUNT(*) FROM merchant_task WHERE xianyu_account_id = #{accountId} " +
            "AND task_type IN ('BARGAIN_FREE_SHIPPING', 'CONFIRM_SHIPMENT') " +
            "AND (status IN (0, 1) OR (status = -1 AND attempt_count < max_attempts))")
    long countPendingPlatformActions(@Param("accountId") Long accountId);
}
