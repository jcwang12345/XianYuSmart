package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.ProductMatrixService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 多店商品驾驶舱和批量任务中心。 */
@RestController
@RequestMapping("/api/product-matrix")
public class ProductMatrixController {

    private final ProductMatrixService productMatrixService;

    public ProductMatrixController(ProductMatrixService productMatrixService) {
        this.productMatrixService = productMatrixService;
    }

    @PostMapping("/products/query")
    public ResultObject<Map<String, Object>> products(@RequestBody(required = false) ProductMatrixService.ProductFilter filter) {
        return ResultObject.success(productMatrixService.list(filter));
    }

    @GetMapping("/accounts/{accountId}/products/{goodsId}")
    public ResultObject<Map<String, Object>> detail(@PathVariable Long accountId, @PathVariable String goodsId) {
        return ResultObject.success(productMatrixService.detail(accountId, goodsId));
    }

    @GetMapping("/accounts/{accountId}/products/{goodsId}/capabilities")
    public ResultObject<Map<String, Object>> capabilities(@PathVariable Long accountId, @PathVariable String goodsId) {
        return ResultObject.success(productMatrixService.capabilities(accountId, goodsId));
    }

    @PutMapping("/accounts/{accountId}/products/{goodsId}/local-details")
    public ResultObject<Map<String, Object>> updateLocalDetails(@PathVariable Long accountId, @PathVariable String goodsId,
                                                                @RequestBody ProductMatrixService.LocalProductUpdate request) {
        return ResultObject.success(productMatrixService.updateLocalDetails(accountId, goodsId, request));
    }

    @PutMapping("/accounts/{accountId}/products/{goodsId}/automation")
    public ResultObject<Map<String, Object>> updateAutomation(@PathVariable Long accountId, @PathVariable String goodsId,
                                                              @RequestBody ProductMatrixService.AutomationUpdate request) {
        return ResultObject.success(productMatrixService.updateAutomation(accountId, goodsId, request));
    }

    @GetMapping("/accounts/{accountId}/products/{goodsId}/events/{eventId}/raw-snapshot")
    public ResultObject<Map<String, Object>> rawSnapshot(@PathVariable Long accountId, @PathVariable String goodsId,
                                                         @PathVariable Long eventId) {
        return ResultObject.success(productMatrixService.rawSnapshotMetadata(accountId, goodsId, eventId));
    }

    @GetMapping("/accounts/{accountId}/products/{goodsId}/events")
    public ResultObject<List<Map<String, Object>>> events(@PathVariable Long accountId,
                                                          @PathVariable String goodsId,
                                                          @RequestParam(required = false) Integer limit) {
        return ResultObject.success(productMatrixService.events(accountId, goodsId, limit));
    }

    @GetMapping("/filters")
    public ResultObject<List<Map<String,Object>>> filters(){return ResultObject.success(productMatrixService.listFilters());}

    @PostMapping("/filters")
    public ResultObject<Map<String,Object>> saveFilter(@RequestBody ProductMatrixService.SavedFilterCommand command){
        return ResultObject.success(productMatrixService.saveFilter(command));
    }

    @DeleteMapping("/filters/{id}")
    public ResultObject<Void> deleteFilter(@PathVariable Long id,@RequestParam String requestId){productMatrixService.deleteFilter(id,requestId);return ResultObject.success(null);}

    @PostMapping("/products/export")
    public ResponseEntity<byte[]> export(@RequestBody ProductExportRequest request){
        String csv=productMatrixService.exportProducts(request.filter(),request.requestId());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("products.csv",StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/batches/preview")
    public ResultObject<ProductMatrixService.BatchPreview> preview(
            @RequestBody ProductMatrixService.BatchRequest request) {
        return ResultObject.success(productMatrixService.previewBatch(request));
    }

    /** 操作类型放入路径，便于权限层对删除和批量改价执行独立授权。 */
    @PostMapping("/batches/{operation}/create")
    public ResultObject<Map<String, Object>> create(@PathVariable String operation,
                                                     @RequestBody ProductMatrixService.BatchRequest request) {
        requireOperationMatches(operation, request.operationType());
        return ResultObject.success(productMatrixService.createBatch(request));
    }

    @GetMapping("/batches")
    public ResultObject<List<Map<String, Object>>> batches(@RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String operationType,
                                                           @RequestParam(required = false) Long accountId,
                                                           @RequestParam(required = false) Long operatorUserId,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(required = false) String createdFrom,
                                                           @RequestParam(required = false) String createdTo,
                                                           @RequestParam(required = false) Integer limit) {
        return ResultObject.success(productMatrixService.batches(new ProductMatrixService.BatchQuery(
                status, operationType, accountId, operatorUserId, search, createdFrom, createdTo, limit)));
    }

    @GetMapping("/batches/{jobId}")
    public ResultObject<Map<String, Object>> batchDetail(@PathVariable Long jobId) {
        return ResultObject.success(productMatrixService.batchDetail(jobId));
    }

    @PostMapping("/batches/{operation}/{jobId}/retry")
    public ResultObject<Map<String, Object>> retry(@PathVariable String operation, @PathVariable Long jobId,
                                                   @RequestBody RetryRequest request) {
        Map<String, Object> batch = productMatrixService.batchDetail(jobId);
        requireOperationMatches(operation, String.valueOf(batch.get("operationType")));
        return ResultObject.success(productMatrixService.retryBatchFailures(jobId, request.requestId(), request.itemIds()));
    }

    @PostMapping("/batches/{jobId}/cancel")
    public ResultObject<Map<String, Object>> cancel(@PathVariable Long jobId, @RequestBody CancelRequest request) {
        return ResultObject.success(productMatrixService.cancelBatch(jobId, request.requestId(), request.reason()));
    }

    @PostMapping("/batches/{jobId}/failures/export")
    public ResponseEntity<byte[]> exportFailures(@PathVariable Long jobId, @RequestBody RequestIdentity request) {
        String csv = productMatrixService.exportBatchFailures(jobId, request.requestId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("product-batch-failures.csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private void requireOperationMatches(String pathOperation, String bodyOperation) {
        String path = pathOperation == null ? "" : pathOperation.replace('-', '_').toUpperCase(Locale.ROOT);
        String body = bodyOperation == null ? "" : bodyOperation.trim().toUpperCase(Locale.ROOT);
        if (!path.equals(body)) throw new BusinessException(400, "路径操作类型与请求内容不一致");
    }

    public record RequestIdentity(String requestId) {}
    public record RetryRequest(String requestId, List<Long> itemIds) {}
    public record CancelRequest(String requestId, String reason) {}
    public record ProductExportRequest(ProductMatrixService.ProductFilter filter,String requestId) {}
}
