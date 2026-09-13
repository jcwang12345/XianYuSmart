package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.exception.BusinessException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 操作记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/operation-log")
public class OperationLogController {
    
    @Autowired
    private OperationLogService operationLogService;
    
    /**
     * 查询操作记录
     */
    @PostMapping("/query")
    public ResultObject<Map<String, Object>> queryLogs(@RequestBody QueryLogsReqDTO reqDTO) {
        try {
            log.info("查询操作记录: accountId={}, type={}, module={}, status={}, page={}, pageSize={}",
                    reqDTO.getAccountId(), reqDTO.getOperationType(), reqDTO.getOperationModule(),
                    reqDTO.getOperationStatus(), reqDTO.getPage(), reqDTO.getPageSize());
            
            // 设置默认值
            if (reqDTO.getPage() == null || reqDTO.getPage() < 1) {
                reqDTO.setPage(1);
            }
            if (reqDTO.getPageSize() == null || reqDTO.getPageSize() < 1) {
                reqDTO.setPageSize(20);
            }
            
            Map<String, Object> result = operationLogService.queryLogs(reqDTO.toQuery());
            
            // 添加调试日志
            log.info("查询结果: total={}, logs={}", result.get("total"), 
                    result.get("logs") != null ? ((java.util.List<?>) result.get("logs")).size() : 0);
            
            return ResultObject.success(result);
            
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("查询操作记录失败", e);
            return ResultObject.failed("查询失败: " + e.getMessage());
        }
    }

    /** 导出本次权限范围内的审计数据；导出动作本身也会记入审计。 */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody QueryLogsReqDTO reqDTO) {
        String csv = operationLogService.exportCsv(reqDTO.toQuery());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("operation-audit.csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 删除旧日志
     */
    @PostMapping("/deleteOld")
    public ResultObject<Integer> deleteOldLogs(@RequestBody DeleteOldLogsReqDTO reqDTO) {
        try {
            log.info("删除旧操作记录: days={}", reqDTO.getDays());
            
            if (reqDTO.getDays() == null || reqDTO.getDays() < 1) {
                return ResultObject.failed("天数必须大于0");
            }
            
            int deleted = operationLogService.deleteOldLogs(reqDTO.getDays());
            
            return ResultObject.success(deleted);
            
        } catch (Exception e) {
            log.error("删除旧操作记录失败", e);
            return ResultObject.failed("删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 查询操作记录请求DTO
     */
    @Data
    public static class QueryLogsReqDTO {
        private Long accountId;           // 账号ID（必填）
        private String operationType;     // 操作类型（可选）
        private String operationModule;   // 操作模块（可选）
        private Integer operationStatus;  // 操作状态（可选）
        private String outcomeState;      // 本地成功/平台确认/部分成功/失败/未知
        private String operatorUsername;  // 操作者（可选）
        private String requestId;         // 贯穿请求ID（可选）
        private Long startTime;           // 毫秒时间戳（可选）
        private Long endTime;             // 毫秒时间戳（可选）
        private String keyword;            // 描述、目标或请求ID关键词
        private Integer page;             // 页码（默认1）
        private Integer pageSize;         // 每页数量（默认20）

        OperationLogService.AuditLogQuery toQuery() {
            return new OperationLogService.AuditLogQuery(accountId, operationType, operationModule,
                    operationStatus, outcomeState, operatorUsername, requestId, startTime, endTime,
                    keyword, page, pageSize);
        }
    }
    
    /**
     * 删除旧日志请求DTO
     */
    @Data
    public static class DeleteOldLogsReqDTO {
        private Integer days;  // 删除多少天之前的日志
    }
}
