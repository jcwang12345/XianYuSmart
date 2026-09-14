package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.ProductBatchExecutionService;
import com.xianyusmart.service.ProductBatchQaMockService;
import com.xianyusmart.service.ProductMatrixService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PRD-04 隔离端到端验收控制器。仅 qa profile 注册；服务还会复核租户、店铺和商品前缀。
 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/product-batches")
public class QaProductBatchController {

    private final ProductBatchQaMockService qaMockService;
    private final ProductBatchExecutionService executionService;
    private final ProductMatrixService productMatrixService;

    public QaProductBatchController(ProductBatchQaMockService qaMockService,
                                    ProductBatchExecutionService executionService,
                                    ProductMatrixService productMatrixService) {
        this.qaMockService = qaMockService;
        this.executionService = executionService;
        this.productMatrixService = productMatrixService;
    }

    @GetMapping("/configuration")
    public ResultObject<Map<String, Object>> configuration() {
        requireEnabled();
        return ResultObject.success(qaMockService.publicConfiguration());
    }

    @PostMapping("/{jobId}/dispatch")
    public ResultObject<Map<String, Object>> dispatch(@PathVariable Long jobId, @RequestBody ControlRequest request) {
        requireEnabled();
        Map<String, Object> result = executionService.dispatchOneQaJob(TenantContext.get(), jobId);
        productMatrixService.recordQaControl(jobId, "DISPATCH", request.requestId(), result);
        return ResultObject.success(productMatrixService.batchDetail(jobId));
    }

    @PostMapping("/{jobId}/drain")
    public ResultObject<Map<String, Object>> drain(@PathVariable Long jobId,
                                                   @RequestParam(defaultValue = "2000") int maxCycles,
                                                   @RequestBody ControlRequest request) {
        requireEnabled();
        Map<String, Object> result = executionService.drainQaJob(TenantContext.get(), jobId, maxCycles);
        productMatrixService.recordQaControl(jobId, "DRAIN", request.requestId(), result);
        return ResultObject.success(productMatrixService.batchDetail(jobId));
    }

    @PostMapping("/{jobId}/prepare-restart")
    public ResultObject<Map<String, Object>> prepareRestart(@PathVariable Long jobId,
                                                            @RequestBody ControlRequest request) {
        requireEnabled();
        Map<String, Object> result = executionService.prepareQaRestartFault(TenantContext.get(), jobId);
        productMatrixService.recordQaControl(jobId, "PREPARE_RESTART", request.requestId(), result);
        return ResultObject.success(productMatrixService.batchDetail(jobId));
    }

    @PostMapping("/{jobId}/recover")
    public ResultObject<Map<String, Object>> recover(@PathVariable Long jobId, @RequestBody ControlRequest request) {
        requireEnabled();
        Map<String, Object> result = executionService.recoverQaJob(TenantContext.get(), jobId);
        productMatrixService.recordQaControl(jobId, "RECOVER", request.requestId(), result);
        return ResultObject.success(productMatrixService.batchDetail(jobId));
    }

    private void requireEnabled() {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
    }

    public record ControlRequest(String requestId) {}
}
