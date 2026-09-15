package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.mapper.XianyuOperationLogMapper;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 操作记录服务实现
 */
@Slf4j
@Service
public class OperationLogServiceImpl implements OperationLogService {
    
    private final XianyuOperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(XianyuOperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }
    
    @Override
    public void log(XianyuOperationLog operationLog) {
        try {
            populateOperator(operationLog);
            // 设置创建时间
            if (operationLog.getCreateTime() == null) {
                operationLog.setCreateTime(System.currentTimeMillis());
            }
            
            operationLogMapper.insert(operationLog);
            log.debug("操作日志已记录: accountId={}, type={}, module={}", 
                    operationLog.getXianyuAccountId(), 
                    operationLog.getOperationType(), 
                    operationLog.getOperationModule());
        } catch (Exception e) {
            log.error("记录操作日志失败", e);
        }
    }
    
    @Override
    public void log(Long accountId, String operationType, String operationDesc, Integer status) {
        log(accountId, operationType, null, operationDesc, status, 
            null, null, null, null, null, null);
    }
    
    @Override
    public void log(Long accountId, String operationType, String operationModule, 
                   String operationDesc, Integer status, String targetType, String targetId,
                   String requestParams, String responseResult, String errorMessage, Integer durationMs) {
        try {
            XianyuOperationLog operationLog = new XianyuOperationLog();
            operationLog.setXianyuAccountId(accountId);
            populateOperator(operationLog);
            operationLog.setOperationType(operationType);
            operationLog.setOperationModule(operationModule);
            operationLog.setOperationDesc(operationDesc);
            operationLog.setOperationStatus(status);
            operationLog.setTargetType(targetType);
            operationLog.setTargetId(targetId);
            operationLog.setRequestParams(requestParams);
            operationLog.setResponseResult(responseResult);
            operationLog.setErrorMessage(errorMessage);
            operationLog.setDurationMs(durationMs);
            operationLog.setCreateTime(System.currentTimeMillis());
            
            operationLogMapper.insert(operationLog);
            log.debug("操作日志已记录: accountId={}, type={}, module={}, status={}", 
                    accountId, operationType, operationModule, status);
        } catch (Exception e) {
            log.error("记录操作日志失败", e);
        }
    }

    private void populateOperator(XianyuOperationLog operationLog) {
        if (operationLog.getOperatorUserId() == null) {
            operationLog.setOperatorUserId(UserContext.getUserId());
        }
        if (operationLog.getOperatorUsername() == null) {
            operationLog.setOperatorUsername(UserContext.getUsername());
        }
    }
    
    @Override
    public Map<String, Object> queryLogs(Long accountId, String operationType, String operationModule,
                                        Integer operationStatus, Integer page, Integer pageSize) {
        return queryLogs(new AuditLogQuery(accountId, operationType, operationModule, operationStatus,
                null, null, null, null, null, null, page, pageSize));
    }

    @Override
    public Map<String, Object> queryLogs(AuditLogQuery query) {
        return queryLogs(query, 200);
    }

    private Map<String, Object> queryLogs(AuditLogQuery query, int maxPageSize) {
        try {
            if (query == null) {
                query = new AuditLogQuery(null, null, null, null, null, null,
                        null, null, null, null, 1, 20);
            }
            validateRange(query.startTime(), query.endTime());
            String outcomeState = normalizeOutcome(query.outcomeState());
            Integer page = query.page();
            Integer pageSize = query.pageSize();
            if (page == null || page < 1) {
                page = 1;
            }
            if (pageSize == null || pageSize < 1) {
                pageSize = 20;
            }
            pageSize = Math.min(pageSize, maxPageSize);
            
            int offset = (page - 1) * pageSize;
            
            // 查询列表
            List<XianyuOperationLog> logs = operationLogMapper.selectByPage(
                    query.accountId(), trim(query.operationType()), trim(query.operationModule()),
                    query.operationStatus(), outcomeState, trim(query.operatorUsername()), trim(query.requestId()),
                    query.startTime(), query.endTime(), trim(query.keyword()), pageSize, offset);
            
            // 查询总数
            Integer total = operationLogMapper.countByCondition(
                    query.accountId(), trim(query.operationType()), trim(query.operationModule()),
                    query.operationStatus(), outcomeState, trim(query.operatorUsername()), trim(query.requestId()),
                    query.startTime(), query.endTime(), trim(query.keyword()));
            
            // 构建返回结果（注意：前端期望的字段名是logs，不是list）
            Map<String, Object> result = new HashMap<>();
            result.put("logs", logs);  // 修改为logs
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("totalPages", (int) Math.ceil((double) total / pageSize));
            
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询操作日志失败", e);
            throw new BusinessException("查询操作日志失败", e);
        }
    }

    @Override
    public String exportCsv(AuditLogQuery query, String exportRequestId) {
        String safeExportRequestId = trim(exportRequestId);
        if (safeExportRequestId == null) {
            throw new BusinessException(400, "导出审计需要requestId");
        }
        AuditLogQuery normalized = query == null
                ? new AuditLogQuery(null, null, null, null, null, null, null,
                    null, null, null, 1, 10_000)
                : new AuditLogQuery(query.accountId(), query.operationType(), query.operationModule(),
                    query.operationStatus(), query.outcomeState(), query.operatorUsername(), query.requestId(),
                    query.startTime(), query.endTime(), query.keyword(), 1, 10_000);
        Map<String, Object> result = queryLogs(normalized, 10_000);
        @SuppressWarnings("unchecked")
        List<XianyuOperationLog> logs = (List<XianyuOperationLog>) result.getOrDefault("logs", List.of());
        StringBuilder csv = new StringBuilder("\uFEFF时间,操作者,账号ID,模块,类型,描述,状态,结果层,来源,请求ID,目标类型,目标ID,错误\r\n");
        for (XianyuOperationLog item : logs) {
            csv.append(csv(item.getCreateTime())).append(',').append(csv(item.getOperatorUsername())).append(',')
                    .append(csv(item.getXianyuAccountId())).append(',').append(csv(item.getOperationModule())).append(',')
                    .append(csv(item.getOperationType())).append(',').append(csv(item.getOperationDesc())).append(',')
                    .append(csv(item.getOperationStatus())).append(',').append(csv(item.getOutcomeState())).append(',')
                    .append(csv(item.getDataSource())).append(',').append(csv(item.getRequestId())).append(',')
                    .append(csv(item.getTargetType())).append(',').append(csv(item.getTargetId())).append(',')
                    .append(csv(item.getErrorMessage())).append("\r\n");
        }
        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(normalized.accountId());
        audit.setOperationType("AUDIT_EXPORT");
        audit.setOperationModule("操作审计");
        audit.setOperationDesc("导出操作审计（最多10000条）");
        audit.setOperationStatus(1);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setRequestId(safeExportRequestId);
        audit.setTargetType("AUDIT_LOG");
        audit.setResponseResult("{\"exportedCount\":" + logs.size() + "}");
        log(audit);
        return csv.toString();
    }

    private void validateRange(Long startTime, Long endTime) {
        if (startTime != null && endTime != null && startTime > endTime) {
            throw new BusinessException(400, "开始时间不能晚于结束时间");
        }
    }

    private String normalizeOutcome(String value) {
        String normalized = trim(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("LOCAL_SUCCESS", "PLATFORM_CONFIRMED", "PARTIAL", "FAILED", "UNKNOWN").contains(normalized)) {
            throw new BusinessException(400, "审计结果层状态无效");
        }
        return normalized;
    }

    private String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
    
    @Override
    public int deleteOldLogs(int days) {
        try {
            long threshold = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
            
            LambdaQueryWrapper<XianyuOperationLog> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.lt(XianyuOperationLog::getCreateTime, threshold);
            
            int deleted = operationLogMapper.delete(queryWrapper);
            log.info("已删除{}天前的操作日志: {}条", days, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("删除旧日志失败", e);
            return 0;
        }
    }
}
