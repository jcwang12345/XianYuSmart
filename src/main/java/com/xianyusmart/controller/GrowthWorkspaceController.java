package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.GrowthResourceService;
import com.xianyusmart.service.GrowthSearchEvidenceService;
import com.xianyusmart.service.GrowthWorkflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** V6 Wave 7 素材、货源、只读增长证据与持久化工作流统一 API。 */
@RestController
@RequestMapping("/api/growth-workspace")
public class GrowthWorkspaceController {

    private final GrowthResourceService resourceService;
    private final GrowthSearchEvidenceService searchService;
    private final GrowthWorkflowService workflowService;

    public GrowthWorkspaceController(GrowthResourceService resourceService,
                                     GrowthSearchEvidenceService searchService,
                                     GrowthWorkflowService workflowService) {
        this.resourceService = resourceService;
        this.searchService = searchService;
        this.workflowService = workflowService;
    }

    @GetMapping("/resources")
    public ResultObject<List<Map<String, Object>>> resources(@RequestParam String type,
                                                              @RequestParam(required = false) Integer status) {
        return ResultObject.success(resourceService.list(type, status));
    }

    @GetMapping("/resources/{resourceId}")
    public ResultObject<Map<String, Object>> resource(@PathVariable Long resourceId) {
        return ResultObject.success(resourceService.detail(resourceId));
    }

    @PostMapping("/resources/versions")
    public ResultObject<Map<String, Object>> createResourceVersion(@RequestBody Map<String, Object> request) {
        return ResultObject.success(resourceService.createVersion(request));
    }

    @PostMapping("/resources/{resourceId}/versions/{version}/activate")
    public ResultObject<Map<String, Object>> activateResourceVersion(@PathVariable Long resourceId,
                                                                      @PathVariable Integer version,
                                                                      @RequestBody Map<String, Object> request) {
        return ResultObject.success(resourceService.activate(resourceId, version, request));
    }

    /** 搜索是平台公开数据只读采样；结果总数和价格结论只代表本次返回样本。 */
    @PostMapping("/searches")
    public ResultObject<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        return ResultObject.success(searchService.search(request));
    }

    @GetMapping("/searches")
    public ResultObject<List<Map<String, Object>>> searches(@RequestParam(required = false) Long accountId,
                                                             @RequestParam(required = false) String searchType,
                                                             @RequestParam(defaultValue = "50") Integer limit) {
        return ResultObject.success(searchService.list(accountId, searchType, limit == null ? 50 : limit));
    }

    @GetMapping("/searches/{snapshotId}")
    public ResultObject<Map<String, Object>> searchSnapshot(@PathVariable Long snapshotId) {
        return ResultObject.success(searchService.get(snapshotId));
    }

    @GetMapping("/workflows")
    public ResultObject<List<Map<String, Object>>> workflows() {
        return ResultObject.success(workflowService.definitions());
    }

    @GetMapping("/workflows/{workflowId}")
    public ResultObject<Map<String, Object>> workflow(@PathVariable Long workflowId) {
        return ResultObject.success(workflowService.definition(workflowId));
    }

    @PostMapping("/workflows/versions")
    public ResultObject<Map<String, Object>> createWorkflowVersion(@RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.saveVersion(request));
    }

    @PostMapping("/workflows/{workflowId}/versions/{version}/activate")
    public ResultObject<Map<String, Object>> activateWorkflowVersion(@PathVariable Long workflowId,
                                                                      @PathVariable Integer version,
                                                                      @RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.activate(workflowId, version, request));
    }

    @PostMapping("/workflows/{workflowId}/preflight")
    public ResultObject<Map<String, Object>> preflight(@PathVariable Long workflowId,
                                                        @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return ResultObject.success(workflowService.preflight(workflowId, integer(body.get("version")),
                longValue(body.get("accountId")), text(body.get("executionMode"))));
    }

    @PostMapping("/workflow-runs")
    public ResultObject<Map<String, Object>> createRun(@RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.createRun(request));
    }

    @GetMapping("/workflow-runs")
    public ResultObject<Map<String, Object>> runs(@RequestParam(required = false) Long workflowId,
                                                  @RequestParam(required = false) Long accountId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") Integer pageNumber,
                                                  @RequestParam(defaultValue = "25") Integer pageSize) {
        return ResultObject.success(workflowService.runPage(workflowId, accountId, status,
                pageNumber == null ? 1 : pageNumber, pageSize == null ? 25 : pageSize));
    }

    @GetMapping("/workflow-runs/{runId}")
    public ResultObject<Map<String, Object>> run(@PathVariable Long runId) {
        return ResultObject.success(workflowService.run(runId));
    }

    @PostMapping("/workflow-runs/{runId}/cancel")
    public ResultObject<Map<String, Object>> cancel(@PathVariable Long runId,
                                                    @RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.cancel(runId, request));
    }

    @PostMapping("/workflow-runs/{runId}/retry-failed")
    public ResultObject<Map<String, Object>> retryFailed(@PathVariable Long runId,
                                                         @RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.retryFailed(runId, request));
    }

    @PostMapping("/workflow-runs/{runId}/compensate")
    public ResultObject<Map<String, Object>> compensate(@PathVariable Long runId,
                                                        @RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.compensate(runId, request));
    }

    @PostMapping("/workflow-runs/{runId}/nodes/{nodeId}/resolve-unknown")
    public ResultObject<Map<String, Object>> resolveUnknown(@PathVariable Long runId,
                                                            @PathVariable String nodeId,
                                                            @RequestBody Map<String, Object> request) {
        return ResultObject.success(workflowService.resolveUnknown(runId, nodeId, request));
    }

    private Integer integer(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { return value instanceof Number number ? number.intValue() : Integer.valueOf(text(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("version 必须是整数"); }
    }

    private Long longValue(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { return value instanceof Number number ? number.longValue() : Long.valueOf(text(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("accountId 必须是整数"); }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
