package com.xianyusmart.mapper;

import com.xianyusmart.entity.XianyuNotificationOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XianyuNotificationOutboxMapper {

    @Insert("INSERT INTO xianyu_notification_outbox " +
            "(tenant_id, channel_id, event_type, xianyu_account_id, dedupe_key, event_id, title, content, data_json) " +
            "VALUES (#{tenantId}, #{channelId}, #{eventType}, #{xianyuAccountId}, #{dedupeKey}, #{eventId}, " +
            "#{title}, #{content}, #{dataJson}) " +
            "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuNotificationOutbox task);

    @Select("SELECT * FROM xianyu_notification_outbox WHERE " +
            "((status IN ('PENDING', 'RETRY_WAIT') AND next_retry_time <= NOW(3)) " +
            "OR (status = 'PROCESSING' AND lease_expire_time < NOW(3))) " +
            "ORDER BY next_retry_time ASC, id ASC LIMIT #{limit}")
    List<XianyuNotificationOutbox> selectDue(@Param("limit") int limit);

    @Update("UPDATE xianyu_notification_outbox SET status = 'PROCESSING', lease_owner = #{workerId}, " +
            "lease_expire_time = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND), " +
            "attempt_count = attempt_count + 1 WHERE id = #{id} AND " +
            "((status IN ('PENDING', 'RETRY_WAIT') AND next_retry_time <= NOW(3)) " +
            "OR (status = 'PROCESSING' AND lease_expire_time < NOW(3)))")
    int claim(@Param("id") Long id, @Param("workerId") String workerId,
              @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE xianyu_notification_outbox SET status = 'SENT', next_retry_time = NULL, " +
            "lease_owner = NULL, lease_expire_time = NULL, last_error_message = NULL " +
            "WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{workerId}")
    int markSent(@Param("id") Long id, @Param("workerId") String workerId);

    @Update("UPDATE xianyu_notification_outbox SET status = #{status}, next_retry_time = #{nextRetryTime}, " +
            "lease_owner = NULL, lease_expire_time = NULL, last_error_message = #{errorMessage} " +
            "WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{workerId}")
    int retryOrFail(@Param("id") Long id, @Param("workerId") String workerId,
                    @Param("status") String status, @Param("nextRetryTime") LocalDateTime nextRetryTime,
                    @Param("errorMessage") String errorMessage);
}
