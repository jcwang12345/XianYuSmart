package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuOperationLog;

import java.util.List;
import java.util.Map;

/**
 * 操作记录服务
 */
public interface OperationLogService {
    
    /**
     * 记录操作日志
     */
    void log(XianyuOperationLog operationLog);

    /**
     * 在业务事务内写入不可丢失的审计；失败必须向上抛出并触发业务回滚。
     */
    void logRequired(XianyuOperationLog operationLog);
    
    /**
     * 记录操作日志（简化版）
     */
    void log(Long accountId, String operationType, String operationDesc, Integer status);
    
    /**
     * 记录操作日志（完整版）
     */
    void log(Long accountId, String operationType, String operationModule, 
             String operationDesc, Integer status, String targetType, String targetId,
             String requestParams, String responseResult, String errorMessage, Integer durationMs);
    
    /**
     * 分页查询操作记录
     */
    Map<String, Object> queryLogs(Long accountId, String operationType, String operationModule,
                                   Integer operationStatus, Integer page, Integer pageSize);

    Map<String, Object> queryLogs(AuditLogQuery query);

    String exportCsv(AuditLogQuery query, String exportRequestId);
    
    /**
     * 删除指定天数之前的日志
     */
    int deleteOldLogs(int days);

    record AuditLogQuery(Long accountId, String operationType, String operationModule,
                         Integer operationStatus, String outcomeState, String operatorUsername,
                         String requestId, Long startTime, Long endTime, String keyword,
                         Integer page, Integer pageSize) {
    }
}
