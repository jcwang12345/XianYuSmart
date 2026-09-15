package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.GrowthWorkflowService;
import com.xianyusmart.service.ProductBatchQaMockService;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Wave 7 隔离工作流夹具：只写本地 QA 数据，节点执行从不触达闲鱼平台。 */
@Profile("qa")
@RestController
@RequestMapping("/api/qa/growth-workspace")
public class QaGrowthWorkspaceController {

    private static final Set<String> SCENARIOS = Set.of(
            "HAPPY_PATH", "FAIL_ONCE", "OUTCOME_UNKNOWN", "CANCEL_PENDING", "LARGE_RUN_SET");

    private final ProductBatchQaMockService qaMockService;
    private final AccountAccessService accountAccessService;
    private final GrowthWorkflowService workflowService;

    public QaGrowthWorkspaceController(ProductBatchQaMockService qaMockService,
                                       AccountAccessService accountAccessService,
                                       GrowthWorkflowService workflowService) {
        this.qaMockService = qaMockService;
        this.accountAccessService = accountAccessService;
        this.workflowService = workflowService;
    }

    @PostMapping("/fixtures")
    @Transactional
    public ResultObject<Map<String, Object>> fixture(@RequestBody FixtureRequest request) {
        requireEnabled(request == null ? null : request.accountId());
        String requestId = validateRequestId(request == null ? null : request.requestId());
        String scenario = request == null || request.scenario() == null
                ? "HAPPY_PATH" : request.scenario().trim().toUpperCase(Locale.ROOT);
        if (!SCENARIOS.contains(scenario)) throw new BusinessException(400, "不支持的 QA 工作流场景");
        int runCount = "LARGE_RUN_SET".equals(scenario)
                ? Math.max(1, Math.min(request.runCount() == null ? 1000 : request.runCount(), 1000)) : 1;

        Map<String, Object> definition = definition(scenario);
        Map<String, Object> saved = workflowService.saveVersion(Map.of(
                "name", "QA Wave7 " + scenario,
                "accountId", request.accountId(),
                "status", 1,
                "definition", definition,
                "changeSummary", "隔离 QA 夹具；平台写入 0",
                "requestId", requestId + "-def"));
        Long workflowId = number(saved.get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) saved.getOrDefault("versions", List.of());
        int version = versions.stream().map(item -> ((Number) item.get("version")).intValue())
                .max(Integer::compareTo).orElseThrow();
        workflowService.activate(workflowId, version, Map.of("requestId", requestId + "-activate"));

        List<Long> runIds = new ArrayList<>(runCount);
        Map<String, Object> firstRun = null;
        for (int i = 1; i <= runCount; i++) {
            String runRequestId = requestId + "-run-" + i;
            Map<String, Object> run = workflowService.createRun(Map.of(
                    "workflowId", workflowId,
                    "version", version,
                    "accountId", request.accountId(),
                    "executionMode", "QA_MOCK",
                    "input", Map.of("qaManualAdvance", true, "scenario", scenario, "ordinal", i),
                    "requestId", runRequestId));
            Long runId = number(run.get("id"));
            runIds.add(runId);
            if (firstRun == null) firstRun = run;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("safeFixture", true);
        result.put("platformNetworkCalls", false);
        result.put("externalNotificationCalls", false);
        result.put("scenario", scenario);
        result.put("accountId", request.accountId());
        result.put("workflowId", workflowId);
        result.put("version", version);
        result.put("runCount", runIds.size());
        result.put("runIds", runIds);
        result.put("firstRun", firstRun);
        result.put("manualAdvance", true);
        return ResultObject.success(result);
    }

    @PostMapping("/runs/{runId}/advance")
    public ResultObject<Map<String, Object>> advance(@PathVariable Long runId,
                                                      @RequestBody(required = false) Map<String, Object> ignored) {
        Map<String, Object> before = workflowService.run(runId);
        requireEnabled(number(before.get("accountId")));
        if (!"QA_MOCK".equals(before.get("executionMode")) || !Boolean.TRUE.equals(before.get("manualDispatch"))) {
            throw new BusinessException(403, "只允许手动推进隔离 QA_MOCK 运行");
        }
        boolean progressed = workflowService.advanceOne(runId);
        Map<String, Object> after = workflowService.run(runId);
        after.put("progressed", progressed);
        after.put("platformNetworkCalls", false);
        return ResultObject.success(after);
    }

    private Map<String, Object> definition(String scenario) {
        String filterFault = "FAIL_ONCE".equals(scenario) ? "FAIL_ONCE" : "";
        String publishFault = "OUTCOME_UNKNOWN".equals(scenario) ? "OUTCOME_UNKNOWN" : "";
        List<Map<String, Object>> nodes = List.of(
                node("trigger", "TRIGGER", "开始", Map.of()),
                node("search", "SEARCH", "只读商机样本", Map.of("keyword", "QA-WAVE7", "limit", 50)),
                node("filter", "FILTER", "证据筛选", config(Map.of("minScore", 60, "limit", 50), filterFault)),
                node("collect", "COLLECT", "写入本地货源", Map.of()),
                node("material", "MATERIAL", "生成本地素材", Map.of()),
                node("publish", "PUBLISH", "发布结构预检", config(Map.of("dryRun", true), publishFault)));
        List<Map<String, Object>> edges = List.of(
                edge("trigger", "search"), edge("search", "filter"), edge("filter", "collect"),
                edge("collect", "material"), edge("material", "publish"));
        return Map.of("nodes", nodes, "edges", edges);
    }

    private Map<String, Object> node(String id, String type, String name, Map<String, Object> config) {
        return Map.of("id", id, "type", type, "name", name, "config", config);
    }

    private Map<String, Object> edge(String source, String target) {
        return Map.of("source", source, "target", target);
    }

    private Map<String, Object> config(Map<String, Object> source, String fault) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        if (!fault.isBlank()) result.put("qaFault", fault);
        return result;
    }

    private void requireEnabled(Long accountId) {
        if (!qaMockService.enabled()) throw new BusinessException(404, "隔离 QA Mock 未启用");
        Map<String, Object> configuration = qaMockService.publicConfiguration();
        Long tenantId = TenantContext.get();
        Long allowedTenantId = ((Number) configuration.get("tenantId")).longValue();
        @SuppressWarnings("unchecked")
        Set<Long> allowedAccounts = (Set<Long>) configuration.get("accountIds");
        if (tenantId == null || !tenantId.equals(allowedTenantId)) {
            throw new BusinessException(403, "当前租户不在隔离 QA 白名单");
        }
        if (accountId == null || !allowedAccounts.contains(accountId)) {
            throw new BusinessException(403, "当前店铺不在隔离 QA 白名单");
        }
        accountAccessService.requireAccess(accountId);
    }

    private String validateRequestId(String value) {
        if (value == null || !value.matches("qa-[A-Za-z0-9._:-]{1,42}")) {
            throw new BusinessException(400, "requestId 必须以 qa- 开头且不超过45个字符");
        }
        return value;
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    public record FixtureRequest(Long accountId, String scenario, String requestId, Integer runCount) { }
}
