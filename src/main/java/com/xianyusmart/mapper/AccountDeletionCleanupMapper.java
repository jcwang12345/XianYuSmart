package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Account-scoped records that are not covered by xianyu_account foreign-key cascades. */
@Mapper
public interface AccountDeletionCleanupMapper {
    @Delete("DELETE FROM xianyu_notification_outbox WHERE xianyu_account_id = #{accountId}")
    int deleteNotificationOutbox(@Param("accountId") Long accountId);

    @Update("UPDATE xianyu_notification_log SET xianyu_account_id = NULL WHERE xianyu_account_id = #{accountId}")
    int detachNotificationLog(@Param("accountId") Long accountId);

    @Update("UPDATE merchant_task SET " +
            "error_message = CASE WHEN status IN (0, 1) THEN '关联账号已删除，任务停止' ELSE error_message END, " +
            "status = CASE WHEN status IN (0, 1) THEN -1 ELSE status END, " +
            "next_retry_time = NULL, xianyu_account_id = NULL " +
            "WHERE xianyu_account_id = #{accountId}")
    int detachMerchantTasks(@Param("accountId") Long accountId);

    @Update("UPDATE merchant_distribution SET xianyu_account_id = NULL WHERE xianyu_account_id = #{accountId}")
    int detachMerchantDistributions(@Param("accountId") Long accountId);

    @Update("UPDATE merchant_resource r SET r.xianyu_account_id = NULL, " +
            "r.status = CASE WHEN EXISTS (SELECT 1 FROM merchant_resource_account l " +
            "WHERE l.resource_id = r.id AND l.xianyu_account_id <> #{accountId}) " +
            "THEN r.status ELSE 0 END WHERE r.xianyu_account_id = #{accountId}")
    int detachMerchantResources(@Param("accountId") Long accountId);

    @Delete("DELETE FROM xianyu_device_profile WHERE xianyu_account_id = #{accountId}")
    int deleteDeviceProfiles(@Param("accountId") Long accountId);

    @Delete("DELETE FROM xianyu_buyer_profile WHERE xianyu_account_id = #{accountId}")
    int deleteBuyerProfiles(@Param("accountId") Long accountId);

    @Update("UPDATE xianyu_fixed_delivery_template t SET t.xianyu_account_id = " +
            "(SELECT MIN(l.xianyu_account_id) FROM xianyu_fixed_delivery_template_account l " +
            "WHERE l.template_id = t.id AND l.xianyu_account_id <> #{accountId}) " +
            "WHERE t.xianyu_account_id = #{accountId} AND EXISTS " +
            "(SELECT 1 FROM xianyu_fixed_delivery_template_account l " +
            "WHERE l.template_id = t.id AND l.xianyu_account_id <> #{accountId})")
    int transferSharedFixedTemplates(@Param("accountId") Long accountId);

    @Delete("DELETE t FROM xianyu_fixed_delivery_template t " +
            "LEFT JOIN xianyu_fixed_delivery_template_account l " +
            "ON l.template_id = t.id AND l.xianyu_account_id <> #{accountId} " +
            "WHERE t.xianyu_account_id = #{accountId} AND l.xianyu_account_id IS NULL")
    int deleteUnsharedFixedTemplates(@Param("accountId") Long accountId);
}
