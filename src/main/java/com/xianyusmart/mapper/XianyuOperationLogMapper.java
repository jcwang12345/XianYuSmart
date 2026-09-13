package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuOperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作记录 Mapper
 */
@Mapper
public interface XianyuOperationLogMapper extends BaseMapper<XianyuOperationLog> {
    
    /**
     * 分页查询操作记录
     */
    @Select("<script>" +
            "SELECT * FROM xianyu_operation_log " +
            "WHERE 1 = 1 " +
            "<if test='accountId != null'> AND xianyu_account_id = #{accountId} </if>" +
            "<if test='operationType != null and operationType != \"\"'>" +
            "  AND operation_type = #{operationType} " +
            "</if>" +
            "<if test='operationModule != null and operationModule != \"\"'>" +
            "  AND operation_module = #{operationModule} " +
            "</if>" +
            "<if test='operationStatus != null'>" +
            "  AND operation_status = #{operationStatus} " +
            "</if>" +
            "<if test='outcomeState != null and outcomeState != \"\"'> AND outcome_state = #{outcomeState} </if>" +
            "<if test='operatorUsername != null and operatorUsername != \"\"'> AND operator_username = #{operatorUsername} </if>" +
            "<if test='requestId != null and requestId != \"\"'> AND request_id = #{requestId} </if>" +
            "<if test='startTime != null'> AND create_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'> AND create_time &lt;= #{endTime} </if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            " AND (operation_desc LIKE CONCAT('%', #{keyword}, '%')" +
            " OR target_id LIKE CONCAT('%', #{keyword}, '%')" +
            " OR request_id LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "ORDER BY create_time DESC, id DESC " +
            "LIMIT #{pageSize} OFFSET #{offset}" +
            "</script>")
    List<XianyuOperationLog> selectByPage(
            @Param("accountId") Long accountId,
            @Param("operationType") String operationType,
            @Param("operationModule") String operationModule,
            @Param("operationStatus") Integer operationStatus,
            @Param("outcomeState") String outcomeState,
            @Param("operatorUsername") String operatorUsername,
            @Param("requestId") String requestId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("keyword") String keyword,
            @Param("pageSize") Integer pageSize,
            @Param("offset") Integer offset
    );
    
    /**
     * 统计操作记录数量
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_operation_log " +
            "WHERE 1 = 1 " +
            "<if test='accountId != null'> AND xianyu_account_id = #{accountId} </if>" +
            "<if test='operationType != null and operationType != \"\"'>" +
            "  AND operation_type = #{operationType} " +
            "</if>" +
            "<if test='operationModule != null and operationModule != \"\"'>" +
            "  AND operation_module = #{operationModule} " +
            "</if>" +
            "<if test='operationStatus != null'>" +
            "  AND operation_status = #{operationStatus} " +
            "</if>" +
            "<if test='outcomeState != null and outcomeState != \"\"'> AND outcome_state = #{outcomeState} </if>" +
            "<if test='operatorUsername != null and operatorUsername != \"\"'> AND operator_username = #{operatorUsername} </if>" +
            "<if test='requestId != null and requestId != \"\"'> AND request_id = #{requestId} </if>" +
            "<if test='startTime != null'> AND create_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'> AND create_time &lt;= #{endTime} </if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            " AND (operation_desc LIKE CONCAT('%', #{keyword}, '%')" +
            " OR target_id LIKE CONCAT('%', #{keyword}, '%')" +
            " OR request_id LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "</script>")
    Integer countByCondition(
            @Param("accountId") Long accountId,
            @Param("operationType") String operationType,
            @Param("operationModule") String operationModule,
            @Param("operationStatus") Integer operationStatus,
            @Param("outcomeState") String outcomeState,
            @Param("operatorUsername") String operatorUsername,
            @Param("requestId") String requestId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("keyword") String keyword
    );
    
    /**
     * 根据账号ID删除操作记录
     */
    @Delete("DELETE FROM xianyu_operation_log WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
