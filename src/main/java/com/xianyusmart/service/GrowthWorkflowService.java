package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * V6 Wave 7 工作流：不可变定义版本、持久化实例、逐节点证据、安全取消与补偿。
 * 当前只开放 DRY_RUN/QA_MOCK，所有发布节点都不会触达闲鱼平台。
 */
@Service
public class GrowthWorkflowService {

    private static final Set<String> RUN_MODES = Set.of("DRY_RUN", "QA_MOCK");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "UNKNOWN");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccountAccessService accountAccessService;
    private final WorkflowDefinitionService definitionService;
    private final OperationLogService operationLogService;

    public GrowthWorkflowService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 AccountAccessService accountAccessService,
                                 WorkflowDefinitionService definitionService,
                                 OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accountAccessService = accountAccessService;
        this.definitionService = definitionService;
        this.operationLogService = operationLogService;
    }

    public List<Map<String, Object>> definitions() {
        return jdbcTemplate.query("""
                SELECT r.id,r.name,r.status,r.xianyu_account_id,r.created_time,r.updated_time,
                       v.id version_id,v.version_no,v.lifecycle_state,v.definition_fingerprint,v.change_summary,
                       v.operator_username,v.published_time,v.created_time version_created_time,
                       (SELECT COUNT(*) FROM growth_workflow_version c
                         WHERE c.tenant_id=r.tenant_id AND c.workflow_resource_id=r.id) version_count,
                       (SELECT COUNT(*) FROM growth_workflow_run wr
                         WHERE wr.tenant_id=r.tenant_id AND wr.workflow_resource_id=r.id) run_count
                  FROM merchant_resource r
                  LEFT JOIN growth_workflow_version v ON v.id=(
                       SELECT vv.id FROM growth_workflow_version vv
                        WHERE vv.tenant_id=r.tenant_id AND vv.workflow_resource_id=r.id
                        ORDER BY (vv.lifecycle_state='ACTIVE') DESC,vv.version_no DESC LIMIT 1)
                 WHERE r.tenant_id=? AND r.resource_type='WORKFLOW'
                 ORDER BY r.updated_time DESC,r.id DESC
                """, (rs, rowNum) -> {
            Long accountId = nullableLong(rs.getObject("xianyu_account_id"));
            if (accountId != null && !accountAccessService.canAccess(accountId)) return null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("name", rs.getString("name"));
            row.put("status", rs.getInt("status"));
            row.put("accountId", accountId);
            row.put("createdTime", instant(rs.getTimestamp("created_time")));
            row.put("updatedTime", instant(rs.getTimestamp("updated_time")));
            row.put("versionCount", rs.getInt("version_count"));
            row.put("runCount", rs.getInt("run_count"));
            Long versionId = nullableLong(rs.getObject("version_id"));
            if (versionId == null) {
                row.put("currentVersion", null);
            } else {
                Map<String, Object> current = new LinkedHashMap<>();
                current.put("id", versionId);
                current.put("version", rs.getInt("version_no"));
                current.put("lifecycleState", rs.getString("lifecycle_state"));
                current.put("fingerprint", rs.getString("definition_fingerprint"));
                current.put("changeSummary", rs.getString("change_summary"));
                current.put("operatorUsername", rs.getString("operator_username"));
                current.put("publishedTime", instant(rs.getTimestamp("published_time")));
                current.put("createdTime", instant(rs.getTimestamp("version_created_time")));
                row.put("currentVersion", current);
            }
            return row;
        }, tenant()).stream().filter(java.util.Objects::nonNull).toList();
    }

    public Map<String, Object> definition(Long workflowId) {
        Map<String, Object> header = workflow(workflowId);
        header.put("versions", jdbcTemplate.query("""
                SELECT id,version_no,lifecycle_state,request_id,request_fingerprint,definition_fingerprint,definition_json,
                       change_summary,operator_user_id,operator_username,activation_request_id,published_time,created_time
                  FROM growth_workflow_version
                 WHERE tenant_id=? AND workflow_resource_id=? ORDER BY version_no DESC
                """, (rs, rowNum) -> {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("id", rs.getLong("id"));
            version.put("version", rs.getInt("version_no"));
            version.put("lifecycleState", rs.getString("lifecycle_state"));
            version.put("requestId", rs.getString("request_id"));
            version.put("requestFingerprint", rs.getString("request_fingerprint"));
            version.put("fingerprint", rs.getString("definition_fingerprint"));
            version.put("definition", jsonMap(rs.getString("definition_json")));
            version.put("changeSummary", rs.getString("change_summary"));
            version.put("operatorUserId", nullableLong(rs.getObject("operator_user_id")));
            version.put("operatorUsername", rs.getString("operator_username"));
            version.put("activationRequestId", rs.getString("activation_request_id"));
            version.put("publishedTime", instant(rs.getTimestamp("published_time")));
            version.put("createdTime", instant(rs.getTimestamp("created_time")));
            return version;
        }, tenant(), workflowId));
        return header;
    }

    @Transactional
    public Map<String, Object> saveVersion(Map<String, Object> request) {
        String requestId = requestId(request);
        String name = text(request.get("name"));
        if (name.isBlank() || name.length() > 512) throw new BusinessException(400, "工作流名称不能为空且不能超过512字符");
        Long accountId = number(request.get("accountId"));
        if (accountId == null) throw new BusinessException(400, "请选择工作流执行账号");
        accountAccessService.requireAccess(accountId);
        Map<String, Object> definition = map(request.get("definition"));
        List<Map<String, Object>> sortedNodes = definitionService.validateAndSort(definition);
        Map<String, Object> normalized = new LinkedHashMap<>(definition);
        normalized.put("nodes", sortedNodes);
        normalized.put("executionBoundary", "DRY_RUN_OR_QA_MOCK_ONLY");
        String definitionFingerprint = fingerprint(normalized);
        Map<String, Object> requestSignature = new LinkedHashMap<>();
        requestSignature.put("workflowId", number(request.get("workflowId")));
        requestSignature.put("name", name);
        requestSignature.put("accountId", accountId);
        requestSignature.put("status", intValue(request.get("status"), 1));
        requestSignature.put("changeSummary", blank(text(request.get("changeSummary"))));
        requestSignature.put("definition", normalized);
        String requestFingerprint = fingerprint(requestSignature);
        List<Map<String, Object>> replay = jdbcTemplate.queryForList("""
                SELECT workflow_resource_id,request_fingerprint FROM growth_workflow_version
                 WHERE tenant_id=? AND request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!replay.isEmpty()) {
            if (!requestFingerprint.equals(text(replay.get(0).get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同工作流定义，请生成新的请求 ID");
            }
            Map<String, Object> result = definition(number(replay.get(0).get("workflow_resource_id")));
            result.put("idempotentReplay", true);
            return result;
        }

        Long workflowId = number(request.get("workflowId"));
        Map<String, Object> before = null;
        if (workflowId == null) {
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource
                    (tenant_id,resource_type,name,status,xianyu_account_id,stock,amount,data_json)
                    VALUES (?,'WORKFLOW',?,?,?,0,0,?)
                    """, tenant(), name, intValue(request.get("status"), 1), accountId, json(normalized));
            workflowId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource_account(resource_id,tenant_id,xianyu_account_id) VALUES (?,?,?)
                    """, workflowId, tenant(), accountId);
        } else {
            before = workflow(workflowId);
            jdbcTemplate.update("""
                    UPDATE merchant_resource SET name=?,status=?,xianyu_account_id=?,data_json=?
                     WHERE tenant_id=? AND id=? AND resource_type='WORKFLOW'
                    """, name, intValue(request.get("status"), intValue(before.get("status"), 1)), accountId,
                    json(normalized), tenant(), workflowId);
            jdbcTemplate.update("DELETE FROM merchant_resource_account WHERE tenant_id=? AND resource_id=?", tenant(), workflowId);
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource_account(resource_id,tenant_id,xianyu_account_id) VALUES (?,?,?)
                    """, workflowId, tenant(), accountId);
        }
        Integer version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM growth_workflow_version
                 WHERE tenant_id=? AND workflow_resource_id=?
                """, Integer.class, tenant(), workflowId);
        jdbcTemplate.update("""
                INSERT INTO growth_workflow_version
                (tenant_id,workflow_resource_id,version_no,lifecycle_state,request_id,request_fingerprint,definition_fingerprint,
                 definition_json,change_summary,operator_user_id,operator_username)
                VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?)
                """, tenant(), workflowId, version, requestId, requestFingerprint, definitionFingerprint, json(normalized),
                blank(text(request.get("changeSummary"))), UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> after = definition(workflowId);
        audit(workflowId, accountId, "WORKFLOW_VERSION_CREATE", requestId, before, after,
                Map.of("version", version, "definitionFingerprint", definitionFingerprint,
                        "requestFingerprint", requestFingerprint, "state", "DRAFT"));
        return after;
    }

    @Transactional
    public Map<String, Object> activate(Long workflowId, Integer version, Map<String, Object> request) {
        Map<String, Object> before = workflow(workflowId);
        String requestId = requestId(request);
        List<Map<String, Object>> target = jdbcTemplate.queryForList("""
                SELECT id,definition_json,activation_request_id FROM growth_workflow_version
                 WHERE tenant_id=? AND workflow_resource_id=? AND version_no=? FOR UPDATE
                """, tenant(), workflowId, version);
        if (target.isEmpty()) throw new BusinessException(404, "工作流版本不存在");
        List<Map<String, Object>> activationReplay = jdbcTemplate.queryForList("""
                SELECT workflow_resource_id,version_no FROM growth_workflow_version
                 WHERE tenant_id=? AND activation_request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!activationReplay.isEmpty()) {
            if (!workflowId.equals(number(activationReplay.get(0).get("workflow_resource_id")))
                    || !version.equals(intValue(activationReplay.get(0).get("version_no"), -1))) {
                throw new BusinessException(409, "requestId 已用于启用其他工作流版本，请生成新的请求 ID");
            }
            Map<String, Object> replay = definition(workflowId);
            replay.put("idempotentReplay", true);
            return replay;
        }
        definitionService.validateAndSort(jsonMap(text(target.get(0).get("definition_json"))));
        jdbcTemplate.update("""
                UPDATE growth_workflow_version SET lifecycle_state='ARCHIVED'
                 WHERE tenant_id=? AND workflow_resource_id=? AND lifecycle_state='ACTIVE'
                """, tenant(), workflowId);
        jdbcTemplate.update("""
                UPDATE growth_workflow_version SET lifecycle_state='ACTIVE',activation_request_id=?,published_time=NOW(3)
                 WHERE tenant_id=? AND workflow_resource_id=? AND version_no=?
                """, requestId, tenant(), workflowId, version);
        jdbcTemplate.update("UPDATE merchant_resource SET data_json=? WHERE tenant_id=? AND id=?",
                target.get(0).get("definition_json"), tenant(), workflowId);
        Map<String, Object> after = definition(workflowId);
        audit(workflowId, number(after.get("accountId")), "WORKFLOW_VERSION_ACTIVATE", requestId,
                before, after, Map.of("activeVersion", version));
        return after;
    }

    public Map<String, Object> preflight(Long workflowId, Integer version, Long requestedAccountId, String mode) {
        Map<String, Object> workflow = workflow(workflowId);
        Long accountId = requestedAccountId == null ? number(workflow.get("accountId")) : requestedAccountId;
        accountAccessService.requireAccess(accountId);
        String executionMode = runMode(mode);
        Map<String, Object> selected = version(workflowId, version, true);
        Map<String, Object> definition = castMap(selected.get("definition"));
        List<Map<String, Object>> nodes = definitionService.validateAndSort(definition);
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String nodeType = text(node.get("type")).toUpperCase();
            Map<String, Object> config = map(node.get("config"));
            List<String> nodeBlockers = new ArrayList<>();
            if ("SEARCH".equals(nodeType) && text(config.get("keyword")).isBlank()) nodeBlockers.add("搜索关键词不能为空");
            String qaFault = text(config.get("qaFault")).toUpperCase();
            if (!qaFault.isBlank() && !Set.of("FAIL_ONCE", "FAIL_ALWAYS", "OUTCOME_UNKNOWN").contains(qaFault)) {
                nodeBlockers.add("QA 故障模式无效");
            }
            if (!qaFault.isBlank() && !"QA_MOCK".equals(executionMode)) {
                nodeBlockers.add("故障注入只允许 QA_MOCK 运行");
            }
            if ("PUBLISH".equals(nodeType) && !Boolean.TRUE.equals(config.get("dryRun"))) {
                nodeBlockers.add("真实发布未开放；请启用仅校验，或使用商品发布 QA Mock 专项");
            }
            blockers.addAll(nodeBlockers.stream().map(item -> text(node.get("name")) + "：" + item).toList());
            checks.add(Map.of(
                    "nodeId", value(node.get("id")), "nodeType", nodeType,
                    "ready", nodeBlockers.isEmpty(), "blockers", nodeBlockers));
        }
        if ("QA_MOCK".equals(executionMode) && !List.of(101L, 102L, 103L).contains(accountId)) {
            blockers.add("QA Mock 运行只允许隔离店铺 101/102/103；真实账号请使用不触达平台的 DRY_RUN");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", blockers.isEmpty());
        result.put("workflowId", workflowId);
        result.put("versionId", selected.get("id"));
        result.put("version", selected.get("version"));
        result.put("accountId", accountId);
        result.put("executionMode", executionMode);
        result.put("nodeCount", nodes.size());
        result.put("checks", checks);
        result.put("blockers", blockers);
        result.put("platformWrite", "NOT_PERFORMED");
        result.put("notice", "本工作流运行通道不会触达闲鱼平台；发布节点仅执行结构化校验。");
        return result;
    }

    @Transactional
    public Map<String, Object> createRun(Map<String, Object> request) {
        String requestId = requestId(request);
        Long workflowId = number(request.get("workflowId"));
        Integer requestedVersion = nullableInt(request.get("version"));
        String mode = runMode(text(request.get("executionMode")));
        Map<String, Object> workflow = workflow(workflowId);
        Long accountId = number(request.get("accountId"));
        if (accountId == null) accountId = number(workflow.get("accountId"));
        Map<String, Object> preflight = preflight(workflowId, requestedVersion, accountId, mode);
        if (!Boolean.TRUE.equals(preflight.get("valid"))) {
            @SuppressWarnings("unchecked")
            List<String> blockers = (List<String>) preflight.get("blockers");
            throw new BusinessException(409, blockers.isEmpty() ? "工作流预检未通过" : blockers.get(0));
        }
        Map<String, Object> runPayload = new TreeMap<>();
        Map<String, Object> runInput = map(request.get("input"));
        boolean manualDispatch = "QA_MOCK".equals(mode) && Boolean.TRUE.equals(runInput.get("qaManualAdvance"));
        runPayload.put("workflowId", workflowId);
        runPayload.put("versionId", preflight.get("versionId"));
        runPayload.put("accountId", accountId);
        runPayload.put("executionMode", mode);
        runPayload.put("input", runInput);
        String fingerprint = fingerprint(runPayload);
        List<Map<String, Object>> replay = jdbcTemplate.queryForList("""
                SELECT id,request_fingerprint FROM growth_workflow_run
                 WHERE tenant_id=? AND request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(text(replay.get(0).get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同工作流运行，请生成新的请求 ID");
            }
            Map<String, Object> result = run(number(replay.get(0).get("id")));
            result.put("idempotentReplay", true);
            return result;
        }
        Long versionId = number(preflight.get("versionId"));
        jdbcTemplate.update("""
                INSERT INTO growth_workflow_run
                (tenant_id,workflow_resource_id,workflow_version_id,xianyu_account_id,request_id,
                 request_fingerprint,execution_mode,status,manual_dispatch,input_json,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,'QUEUED',?,?,?,?)
                """, tenant(), workflowId, versionId, accountId, requestId, fingerprint, mode,
                manualDispatch ? 1 : 0, json(runInput), UserContext.getUserId(), UserContext.getUsername());
        Long runId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> version = versionById(versionId);
        List<Map<String, Object>> nodes = definitionService.validateAndSort(castMap(version.get("definition")));
        int sequence = 0;
        for (Map<String, Object> node : nodes) {
            sequence++;
            String nodeId = text(node.get("id"));
            jdbcTemplate.update("""
                    INSERT INTO growth_workflow_node_run
                    (tenant_id,workflow_run_id,sequence_no,node_id,node_type,node_name,status,idempotency_key,input_json)
                    VALUES (?,?,?,?,?,?,'PENDING',?,?)
                    """, tenant(), runId, sequence, nodeId, text(node.get("type")).toUpperCase(),
                    text(node.get("name")).isBlank() ? text(node.get("type")) : text(node.get("name")),
                    requestId + ":" + nodeId, json(map(node.get("config"))));
        }
        event(runId, null, "RUN_CREATED", null, "QUEUED", "工作流运行已排队，等待逐节点执行", preflight, requestId);
        Map<String, Object> result = run(runId);
        audit(runId, accountId, "WORKFLOW_RUN_CREATE", requestId, null, result,
                Map.of("status", "QUEUED", "versionId", versionId, "executionMode", mode));
        return result;
    }

    public List<Map<String, Object>> runs(Long workflowId, Long accountId, String status, int limit) {
        if (accountId != null) accountAccessService.requireAccess(accountId);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList("""
                SELECT id FROM growth_workflow_run WHERE tenant_id=?
                 AND (? IS NULL OR workflow_resource_id=?) AND (? IS NULL OR xianyu_account_id=?)
                 AND (?='' OR status=?) ORDER BY created_time DESC,id DESC LIMIT ?
                """, tenant(), workflowId, workflowId, accountId, accountId, text(status).toUpperCase(),
                text(status).toUpperCase(), safeLimit).stream().map(row -> run(number(row.get("id")))).toList();
    }

    public Map<String, Object> runPage(Long workflowId, Long accountId, String status, int pageNumber, int pageSize) {
        String normalizedStatus = text(status).toUpperCase();
        int safePage = Math.max(1, pageNumber);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        StringBuilder where = new StringBuilder(" WHERE r.tenant_id=?");
        List<Object> params = new ArrayList<>();
        params.add(tenant());
        if (workflowId != null) {
            where.append(" AND r.workflow_resource_id=?");
            params.add(workflowId);
        }
        if (!normalizedStatus.isBlank()) {
            where.append(" AND r.status=?");
            params.add(normalizedStatus);
        }
        appendAccountScope(where, params, "r.xianyu_account_id", accountId);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM growth_workflow_run r" + where,
                Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbcTemplate.query("""
                SELECT r.id,r.workflow_resource_id,r.workflow_version_id,r.xianyu_account_id,r.request_id,
                       r.execution_mode,r.status,r.current_node_id,r.cancel_requested,r.manual_dispatch,
                       r.error_message,r.lock_version,r.operator_username,r.started_time,r.completed_time,
                       r.created_time,r.updated_time,w.name workflow_name,v.version_no,
                       COALESCE(n.node_count,0) node_count,COALESCE(n.succeeded_count,0) succeeded_count,
                       COALESCE(n.failed_count,0) failed_count,COALESCE(n.unknown_count,0) unknown_count,
                       COALESCE(n.pending_count,0) pending_count,COALESCE(n.cancelled_count,0) cancelled_count,
                       COALESCE(e.event_count,0) event_count
                  FROM growth_workflow_run r
                  JOIN merchant_resource w ON w.tenant_id=r.tenant_id AND w.id=r.workflow_resource_id
                  JOIN growth_workflow_version v ON v.tenant_id=r.tenant_id AND v.id=r.workflow_version_id
                  LEFT JOIN (
                       SELECT tenant_id,workflow_run_id,COUNT(*) node_count,
                              SUM(status='SUCCEEDED') succeeded_count,SUM(status='FAILED') failed_count,
                              SUM(status='UNKNOWN') unknown_count,SUM(status='PENDING') pending_count,
                              SUM(status='CANCELLED') cancelled_count
                         FROM growth_workflow_node_run GROUP BY tenant_id,workflow_run_id
                  ) n ON n.tenant_id=r.tenant_id AND n.workflow_run_id=r.id
                  LEFT JOIN (
                       SELECT tenant_id,workflow_run_id,COUNT(*) event_count
                         FROM growth_workflow_event GROUP BY tenant_id,workflow_run_id
                  ) e ON e.tenant_id=r.tenant_id AND e.workflow_run_id=r.id
                """ + where + " ORDER BY r.created_time DESC,r.id DESC LIMIT ? OFFSET ?", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("workflowId", rs.getLong("workflow_resource_id"));
            row.put("workflowName", rs.getString("workflow_name"));
            row.put("versionId", rs.getLong("workflow_version_id"));
            row.put("version", rs.getInt("version_no"));
            row.put("accountId", rs.getLong("xianyu_account_id"));
            row.put("requestId", rs.getString("request_id"));
            row.put("executionMode", rs.getString("execution_mode"));
            row.put("status", rs.getString("status"));
            row.put("currentNodeId", rs.getString("current_node_id"));
            row.put("cancelRequested", rs.getInt("cancel_requested") == 1);
            row.put("manualDispatch", rs.getInt("manual_dispatch") == 1);
            row.put("error", rs.getString("error_message"));
            row.put("lockVersion", rs.getInt("lock_version"));
            row.put("operatorUsername", rs.getString("operator_username"));
            row.put("startedTime", instant(rs.getTimestamp("started_time")));
            row.put("completedTime", instant(rs.getTimestamp("completed_time")));
            row.put("createdTime", instant(rs.getTimestamp("created_time")));
            row.put("updatedTime", instant(rs.getTimestamp("updated_time")));
            row.put("nodeCount", rs.getInt("node_count"));
            row.put("succeededCount", rs.getInt("succeeded_count"));
            row.put("failedCount", rs.getInt("failed_count"));
            row.put("unknownCount", rs.getInt("unknown_count"));
            row.put("pendingCount", rs.getInt("pending_count"));
            row.put("cancelledCount", rs.getInt("cancelled_count"));
            row.put("eventCount", rs.getInt("event_count"));
            row.put("platformWrite", "NOT_PERFORMED");
            return row;
        }, pageParams.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0L : total);
        result.put("pageNumber", safePage);
        result.put("pageSize", safeSize);
        result.put("hasMore", (long) safePage * safeSize < (total == null ? 0L : total));
        result.put("source", "LOCAL_PERSISTED_WORKFLOW_RUNS");
        result.put("syncedAt", Instant.now());
        return result;
    }

    private void appendAccountScope(StringBuilder where, List<Object> params,
                                    String column, Long requestedAccountId) {
        if (requestedAccountId != null) {
            accountAccessService.requireAccess(requestedAccountId);
            where.append(" AND ").append(column).append("=?");
            params.add(requestedAccountId);
            return;
        }
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return;
        if (scope.accountIds().isEmpty()) {
            where.append(" AND 1=0");
            return;
        }
        where.append(" AND ").append(column).append(" IN (");
        boolean first = true;
        for (Long allowed : scope.accountIds().stream().sorted().toList()) {
            if (!first) where.append(',');
            where.append('?');
            params.add(allowed);
            first = false;
        }
        where.append(')');
    }

    public Map<String, Object> run(Long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.id,r.workflow_resource_id,r.workflow_version_id,r.xianyu_account_id,r.request_id,
                       r.request_fingerprint,r.execution_mode,r.status,r.current_node_id,r.cancel_requested,
                       r.manual_dispatch,r.input_json,r.output_json,r.error_message,r.lock_version,r.operator_user_id,r.operator_username,
                       r.started_time,r.completed_time,r.created_time,r.updated_time,w.name workflow_name,v.version_no
                  FROM growth_workflow_run r
                  JOIN merchant_resource w ON w.tenant_id=r.tenant_id AND w.id=r.workflow_resource_id
                  JOIN growth_workflow_version v ON v.tenant_id=r.tenant_id AND v.id=r.workflow_version_id
                 WHERE r.tenant_id=? AND r.id=?
                """, tenant(), runId);
        if (rows.isEmpty()) throw new BusinessException(404, "工作流运行不存在");
        Map<String, Object> raw = rows.get(0);
        Long accountId = number(raw.get("xianyu_account_id"));
        accountAccessService.requireAccess(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", runId);
        result.put("workflowId", number(raw.get("workflow_resource_id")));
        result.put("workflowName", raw.get("workflow_name"));
        result.put("versionId", number(raw.get("workflow_version_id")));
        result.put("version", intValue(raw.get("version_no"), 0));
        result.put("accountId", accountId);
        result.put("requestId", raw.get("request_id"));
        result.put("requestFingerprint", raw.get("request_fingerprint"));
        result.put("executionMode", raw.get("execution_mode"));
        result.put("status", raw.get("status"));
        result.put("currentNodeId", raw.get("current_node_id"));
        result.put("cancelRequested", intValue(raw.get("cancel_requested"), 0) == 1);
        result.put("manualDispatch", intValue(raw.get("manual_dispatch"), 0) == 1);
        result.put("input", jsonMap(text(raw.get("input_json"))));
        result.put("output", jsonMap(text(raw.get("output_json"))));
        result.put("error", raw.get("error_message"));
        result.put("lockVersion", intValue(raw.get("lock_version"), 0));
        result.put("operatorUserId", number(raw.get("operator_user_id")));
        result.put("operatorUsername", raw.get("operator_username"));
        result.put("startedTime", instant(raw.get("started_time")));
        result.put("completedTime", instant(raw.get("completed_time")));
        result.put("createdTime", instant(raw.get("created_time")));
        result.put("updatedTime", instant(raw.get("updated_time")));
        result.put("platformWrite", "NOT_PERFORMED");
        result.put("nodes", nodeRuns(runId));
        result.put("events", events(runId));
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String, Object> cancel(Long runId, Map<String, Object> request) {
        ActionGuard guard = beginAction(runId, "CANCEL", request);
        if (guard.replay() != null) return guard.replay();
        String requestId = guard.requestId();
        Map<String, Object> before = run(runId);
        String status = text(before.get("status"));
        if (!TERMINAL.contains(status)) {
            jdbcTemplate.update("""
                    UPDATE growth_workflow_run SET cancel_requested=1,lock_version=lock_version+1
                     WHERE tenant_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                    """, tenant(), runId);
            applyCancellation(runId, requestId);
        }
        Map<String, Object> after = run(runId);
        if (!TERMINAL.contains(status)) {
            audit(runId, number(after.get("accountId")), "WORKFLOW_RUN_CANCEL", requestId, before, after,
                    Map.of("before", status, "after", after.get("status")));
        }
        completeAction(guard, after);
        return after;
    }

    @Transactional
    public Map<String, Object> retryFailed(Long runId, Map<String, Object> request) {
        ActionGuard guard = beginAction(runId, "RETRY_FAILED", request);
        if (guard.replay() != null) return guard.replay();
        String requestId = guard.requestId();
        Map<String, Object> before = run(runId);
        List<String> requestedNodes = stringList(request.get("nodeIds"));
        List<Map<String, Object>> failed = nodeRuns(runId).stream()
                .filter(node -> "FAILED".equals(node.get("status")))
                .filter(node -> requestedNodes.isEmpty() || requestedNodes.contains(text(node.get("nodeId"))))
                .toList();
        if (failed.isEmpty()) throw new BusinessException(409, "没有可重试的失败节点");
        for (Map<String, Object> node : failed) {
            jdbcTemplate.update("""
                    UPDATE growth_workflow_node_run
                       SET status='PENDING',error_message=NULL,started_time=NULL,completed_time=NULL
                     WHERE tenant_id=? AND workflow_run_id=? AND node_id=? AND status='FAILED'
                    """, tenant(), runId, node.get("nodeId"));
        }
        jdbcTemplate.update("""
                UPDATE growth_workflow_run SET status='QUEUED',cancel_requested=0,current_node_id=NULL,
                       error_message=NULL,completed_time=NULL,lock_version=lock_version+1
                 WHERE tenant_id=? AND id=?
                """, tenant(), runId);
        event(runId, null, "FAILED_NODES_REQUEUED", text(before.get("status")), "QUEUED",
                "已仅重排选中的失败节点，成功节点保持不变", Map.of("nodeIds", failed.stream().map(n -> n.get("nodeId")).toList()), requestId);
        Map<String, Object> after = run(runId);
        audit(runId, number(after.get("accountId")), "WORKFLOW_RUN_RETRY_FAILED", requestId,
                before, after, Map.of("retriedNodeIds", failed.stream().map(n -> n.get("nodeId")).toList()));
        completeAction(guard, after);
        return after;
    }

    @Transactional
    public Map<String, Object> compensate(Long runId, Map<String, Object> request) {
        ActionGuard guard = beginAction(runId, "COMPENSATE", request);
        if (guard.replay() != null) return guard.replay();
        String requestId = guard.requestId();
        Map<String, Object> before = run(runId);
        if (!TERMINAL.contains(text(before.get("status")))) throw new BusinessException(409, "运行结束后才能执行补偿");
        if ("UNKNOWN".equals(text(before.get("status")))) {
            throw new BusinessException(409, "结果未知时禁止补偿，请先依据回执完成人工核对");
        }
        List<Map<String, Object>> nodes = new ArrayList<>(nodeRuns(runId));
        Collections.reverse(nodes);
        List<String> compensated = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (!"SUCCEEDED".equals(node.get("status"))) continue;
            String type = text(node.get("nodeType"));
            // Trigger/search/filter/publish are evidence or dry-run nodes. Marking
            // them as compensated would falsely imply that an external effect
            // was reversed; only the two nodes that create local resources have
            // a real, safe compensation action.
            if (!Set.of("COLLECT", "MATERIAL").contains(type)) continue;
            if ("SUCCEEDED".equals(node.get("compensationStatus"))) continue;
            Map<String, Object> output = castMap(node.get("output"));
            for (Long resourceId : longList(output.get("resourceIds"))) {
                jdbcTemplate.update("UPDATE merchant_resource SET status=0 WHERE tenant_id=? AND id=?", tenant(), resourceId);
            }
            jdbcTemplate.update("""
                    UPDATE growth_workflow_node_run SET compensation_status='SUCCEEDED',compensation_json=?
                     WHERE tenant_id=? AND id=?
                    """, json(Map.of("mode", "SAFE_LOCAL_DEACTIVATE", "platformWrite", "NOT_PERFORMED")),
                    tenant(), node.get("id"));
            compensated.add(text(node.get("nodeId")));
        }
        event(runId, null, "RUN_COMPENSATED", text(before.get("status")), text(before.get("status")),
                "本地派生资源已停用；未删除证据，也未触达平台", Map.of("nodeIds", compensated), requestId);
        Map<String, Object> after = run(runId);
        audit(runId, number(after.get("accountId")), "WORKFLOW_RUN_COMPENSATE", requestId,
                before, after, Map.of("compensatedNodeIds", compensated, "platformWrite", "NOT_PERFORMED"));
        completeAction(guard, after);
        return after;
    }

    @Transactional
    public Map<String, Object> resolveUnknown(Long runId, String nodeId, Map<String, Object> request) {
        Map<String, Object> actionRequest = map(request);
        actionRequest.put("nodeId", text(nodeId));
        ActionGuard guard = beginAction(runId, "RESOLVE_UNKNOWN", actionRequest);
        if (guard.replay() != null) return guard.replay();
        String normalizedNodeId = text(nodeId);
        String resolution = text(request.get("resolution")).toUpperCase();
        Map<String, Object> evidence = map(request.get("evidence"));
        if (!Set.of("SUCCEEDED", "FAILED").contains(resolution)) {
            throw new BusinessException(400, "人工核对结果仅支持 SUCCEEDED 或 FAILED");
        }
        if (normalizedNodeId.isBlank() || evidence.isEmpty()) {
            throw new BusinessException(400, "人工核对必须指定节点并填写回执证据");
        }
        Map<String, Object> before = run(runId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,node_name FROM growth_workflow_node_run
                 WHERE tenant_id=? AND workflow_run_id=? AND node_id=? AND status='UNKNOWN' FOR UPDATE
                """, tenant(), runId, normalizedNodeId);
        if (rows.isEmpty()) throw new BusinessException(409, "该节点不是待核对的结果未知状态");
        Long nodeRunId = number(rows.get(0).get("id"));
        Map<String, Object> resolutionOutput = new LinkedHashMap<>();
        resolutionOutput.put("manuallyResolved", true);
        resolutionOutput.put("resolution", resolution);
        resolutionOutput.put("evidence", evidence);
        resolutionOutput.put("platformWrite", "NOT_PERFORMED_BY_RESOLUTION");
        jdbcTemplate.update("""
                UPDATE growth_workflow_node_run SET status=?,output_json=?,error_message=?,completed_time=NOW(3)
                 WHERE tenant_id=? AND id=? AND status='UNKNOWN'
                """, resolution, json(resolutionOutput),
                "FAILED".equals(resolution) ? "人工核对确认节点失败" : null, tenant(), nodeRunId);
        String runState = "SUCCEEDED".equals(resolution) ? "QUEUED" : "FAILED";
        jdbcTemplate.update("""
                UPDATE growth_workflow_run SET status=?,current_node_id=NULL,error_message=?,completed_time=NULL,
                       lock_version=lock_version+1 WHERE tenant_id=? AND id=?
                """, runState, "FAILED".equals(resolution) ? "人工核对确认节点失败" : null, tenant(), runId);
        event(runId, nodeRunId, "NODE_UNKNOWN_RESOLVED", "UNKNOWN", resolution,
                text(rows.get(0).get("node_name")) + "已由人工依据回执核对为" + ("SUCCEEDED".equals(resolution) ? "成功" : "失败"),
                resolutionOutput, guard.requestId());
        Map<String, Object> after = run(runId);
        audit(runId, number(after.get("accountId")), "WORKFLOW_RUN_RESOLVE_UNKNOWN", guard.requestId(),
                before, after, Map.of("nodeId", normalizedNodeId, "resolution", resolution));
        completeAction(guard, after);
        return after;
    }

    /** 调度器每次只推进一个节点，使取消和重启都能在节点边界生效。 */
    public int processPendingRuns(int limit) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT id FROM growth_workflow_run
                 WHERE status IN ('QUEUED','RUNNING') AND manual_dispatch=0 ORDER BY updated_time,id LIMIT ?
                """, Long.class, Math.max(1, Math.min(limit, 50)));
        int progressed = 0;
        for (Long id : ids) {
            try {
                if (advanceOne(id)) progressed++;
            } catch (Exception e) {
                // 单运行失败已持久化；不能阻塞其他租户/工作流。
            }
        }
        return progressed;
    }

    @Transactional
    public boolean advanceOne(Long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tenant_id,xianyu_account_id,request_id,execution_mode,status,cancel_requested,input_json
                  FROM growth_workflow_run WHERE id=? FOR UPDATE
                """, runId);
        if (rows.isEmpty()) return false;
        Map<String, Object> run = rows.get(0);
        Long runTenant = number(run.get("tenant_id"));
        String status = text(run.get("status"));
        if (TERMINAL.contains(status)) return false;
        if (intValue(run.get("cancel_requested"), 0) == 1) {
            applyCancellationForTenant(runTenant, runId, text(run.get("request_id")));
            return true;
        }
        // DRY_RUN/QA_MOCK 不包含真实平台写；崩溃遗留的 RUNNING 节点可安全回到待执行。
        jdbcTemplate.update("""
                UPDATE growth_workflow_node_run SET status='PENDING',started_time=NULL,
                       error_message='进程中断后安全恢复；该通道未执行平台写'
                 WHERE tenant_id=? AND workflow_run_id=? AND status='RUNNING'
                   AND updated_time<DATE_SUB(NOW(3),INTERVAL 2 MINUTE)
                """, runTenant, runId);
        List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                SELECT id,sequence_no,node_id,node_type,node_name,input_json,attempt_count
                  FROM growth_workflow_node_run
                 WHERE tenant_id=? AND workflow_run_id=? AND status='PENDING'
                 ORDER BY sequence_no LIMIT 1
                """, runTenant, runId);
        if (pending.isEmpty()) {
            // 另一执行器或崩溃前的节点仍标记为 RUNNING 时不能把整次运行误判为成功。
            // 未超过恢复窗口先等待；超过窗口后上面的更新会把它安全放回 PENDING。
            if (countNodes(runTenant, runId, "RUNNING") > 0) return false;
            if (countNodes(runTenant, runId, "UNKNOWN") > 0) {
                jdbcTemplate.update("""
                        UPDATE growth_workflow_run SET status='UNKNOWN',completed_time=NOW(3),
                               error_message='存在结果未知节点，必须人工核对',lock_version=lock_version+1
                         WHERE tenant_id=? AND id=?
                        """, runTenant, runId);
                return true;
            }
            int failed = countNodes(runTenant, runId, "FAILED");
            String finalState = failed == 0 ? "SUCCEEDED" : "PARTIAL";
            jdbcTemplate.update("""
                    UPDATE growth_workflow_run SET status=?,current_node_id=NULL,completed_time=NOW(3),
                           output_json=?,lock_version=lock_version+1 WHERE tenant_id=? AND id=?
                    """, finalState, json(runSummary(runTenant, runId)), runTenant, runId);
            eventForTenant(runTenant, runId, null, "RUN_COMPLETED", status, finalState,
                    failed == 0 ? "工作流全部节点已完成" : "工作流部分完成，请处理失败节点",
                    runSummary(runTenant, runId), text(run.get("request_id")));
            return true;
        }
        Map<String, Object> node = pending.get(0);
        Long nodeRunId = number(node.get("id"));
        int claimed = jdbcTemplate.update("""
                UPDATE growth_workflow_node_run SET status='RUNNING',attempt_count=attempt_count+1,
                       started_time=NOW(3),error_message=NULL
                 WHERE tenant_id=? AND id=? AND status='PENDING'
                """, runTenant, nodeRunId);
        if (claimed == 0) return false;
        jdbcTemplate.update("""
                UPDATE growth_workflow_run SET status='RUNNING',current_node_id=?,
                       started_time=COALESCE(started_time,NOW(3)),lock_version=lock_version+1
                 WHERE tenant_id=? AND id=?
                """, node.get("node_id"), runTenant, runId);
        eventForTenant(runTenant, runId, nodeRunId, "NODE_STARTED", "PENDING", "RUNNING",
                text(node.get("node_name")) + "开始执行", Map.of("nodeType", node.get("node_type")),
                text(run.get("request_id")));
        try {
            Map<String, Object> output = executeNode(runTenant, runId, run, node);
            jdbcTemplate.update("""
                    UPDATE growth_workflow_node_run SET status='SUCCEEDED',output_json=?,completed_time=NOW(3),
                           error_message=NULL WHERE tenant_id=? AND id=?
                    """, json(output), runTenant, nodeRunId);
            eventForTenant(runTenant, runId, nodeRunId, "NODE_SUCCEEDED", "RUNNING", "SUCCEEDED",
                    text(node.get("node_name")) + "执行完成", output, text(run.get("request_id")));
        } catch (OutcomeUnknownException e) {
            String error = trim(e.getMessage());
            jdbcTemplate.update("""
                    UPDATE growth_workflow_node_run SET status='UNKNOWN',error_message=?,completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, error, runTenant, nodeRunId);
            jdbcTemplate.update("""
                    UPDATE growth_workflow_run SET status='UNKNOWN',error_message=?,current_node_id=?,
                           completed_time=NOW(3),lock_version=lock_version+1 WHERE tenant_id=? AND id=?
                    """, error, node.get("node_id"), runTenant, runId);
            eventForTenant(runTenant, runId, nodeRunId, "NODE_OUTCOME_UNKNOWN", "RUNNING", "UNKNOWN",
                    text(node.get("node_name")) + "结果未知；禁止自动重试，必须先人工核对",
                    Map.of("error", error, "autoRetry", false, "platformWrite", "NOT_PERFORMED"),
                    text(run.get("request_id")));
        } catch (Exception e) {
            String error = trim(e.getMessage());
            jdbcTemplate.update("""
                    UPDATE growth_workflow_node_run SET status='FAILED',error_message=?,completed_time=NOW(3)
                     WHERE tenant_id=? AND id=?
                    """, error, runTenant, nodeRunId);
            jdbcTemplate.update("""
                    UPDATE growth_workflow_run SET status='FAILED',error_message=?,current_node_id=?,
                           completed_time=NOW(3),lock_version=lock_version+1 WHERE tenant_id=? AND id=?
                    """, error, node.get("node_id"), runTenant, runId);
            eventForTenant(runTenant, runId, nodeRunId, "NODE_FAILED", "RUNNING", "FAILED",
                    text(node.get("node_name")) + "执行失败，可仅重试失败节点", Map.of("error", error),
                    text(run.get("request_id")));
        }
        return true;
    }

    private Map<String, Object> executeNode(Long tenantId, Long runId, Map<String, Object> run,
                                            Map<String, Object> node) {
        String type = text(node.get("node_type"));
        String mode = text(run.get("execution_mode"));
        Map<String, Object> config = jsonMap(text(node.get("input_json")));
        String qaFault = text(config.get("qaFault")).toUpperCase();
        int attempt = intValue(node.get("attempt_count"), 0) + 1;
        if (!qaFault.isBlank() && !"QA_MOCK".equals(mode)) {
            throw new BusinessException(409, "故障注入只允许 QA_MOCK 运行");
        }
        if ("FAIL_ALWAYS".equals(qaFault) || ("FAIL_ONCE".equals(qaFault) && attempt == 1)) {
            throw new BusinessException(502, "QA Mock 节点故障（未执行任何平台请求）");
        }
        if ("OUTCOME_UNKNOWN".equals(qaFault) && attempt == 1) {
            throw new OutcomeUnknownException("QA Mock 返回结果未知（未执行任何平台请求）");
        }
        Map<String, Object> previous = previousOutputs(tenantId, runId);
        return switch (type) {
            case "TRIGGER" -> Map.of("triggered", true, "mode", mode, "platformWrite", "NOT_PERFORMED");
            case "SEARCH" -> executeSearchFixture(runId, config, mode);
            case "FILTER" -> executeFilter(previous, config);
            case "COLLECT" -> executeLocalResources(tenantId, runId, run, node, previous, "SUPPLY");
            case "MATERIAL" -> executeLocalResources(tenantId, runId, run, node, previous, "MATERIAL");
            case "PUBLISH" -> Map.of(
                    "validated", true,
                    "candidateCount", candidateList(previous).size(),
                    "executionMode", mode,
                    "platformWrite", "NOT_PERFORMED",
                    "outcomeState", "LOCAL_PREFLIGHT_SUCCESS",
                    "notice", "发布节点仅完成结构化校验；未向闲鱼发送请求");
            default -> throw new BusinessException(400, "不支持的工作流节点：" + type);
        };
    }

    private Map<String, Object> executeSearchFixture(Long runId, Map<String, Object> config, String mode) {
        String keyword = text(config.get("keyword"));
        if (keyword.isBlank()) throw new BusinessException(400, "搜索关键词不能为空");
        int limit = Math.max(1, Math.min(intValue(config.get("limit"), 10), 50));
        if (!"QA_MOCK".equals(mode)) {
            return Map.of("items", List.of(), "sampleCount", 0, "coverageStatus", "DRY_RUN_NO_COLLECTION",
                    "source", "LOCAL_PREFLIGHT", "platformWrite", "NOT_PERFORMED");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= limit; i++) {
            items.add(Map.of(
                    "itemId", "QA-WF-" + runId + "-" + i,
                    "title", keyword + " 候选 " + i,
                    "price", BigDecimal.valueOf(20L + i),
                    "opportunityScore", 55 + (i % 5) * 10,
                    "sourceUrl", "https://qa.invalid/items/" + runId + "/" + i,
                    "source", "QA_FIXTURE"));
        }
        return Map.of("items", items, "sampleCount", items.size(), "coverageStatus", "QA_COMPLETE",
                "source", "QA_FIXTURE", "platformWrite", "NOT_PERFORMED");
    }

    private Map<String, Object> executeFilter(Map<String, Object> previous, Map<String, Object> config) {
        int minScore = Math.max(0, Math.min(intValue(config.get("minScore"), 60), 100));
        int limit = Math.max(1, Math.min(intValue(config.get("limit"), 20), 50));
        List<Map<String, Object>> filtered = candidateList(previous).stream()
                .filter(item -> intValue(item.get("opportunityScore"), 0) >= minScore)
                .limit(limit).toList();
        return Map.of("items", filtered, "sampleCount", filtered.size(), "minimumScore", minScore,
                "platformWrite", "NOT_PERFORMED");
    }

    private Map<String, Object> executeLocalResources(Long tenantId, Long runId, Map<String, Object> run,
                                                      Map<String, Object> node, Map<String, Object> previous,
                                                      String resourceType) {
        List<Map<String, Object>> candidates = candidateList(previous);
        List<Long> ids = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> candidate : candidates) {
            index++;
            String effectKey = text(run.get("request_id")) + ":" + text(node.get("node_id")) + ":" + index;
            List<Long> existing = jdbcTemplate.query("""
                    SELECT id FROM merchant_resource WHERE tenant_id=? AND resource_type=?
                     AND JSON_VALID(data_json)
                     AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.workflowEffectKey'))=? LIMIT 1
                    """, (rs, rowNum) -> rs.getLong(1), tenantId, resourceType, effectKey);
            if (!existing.isEmpty()) {
                ids.add(existing.get(0));
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>(candidate);
            payload.put("workflowRunId", runId);
            payload.put("workflowEffectKey", effectKey);
            payload.put("sourceType", "QA_FIXTURE");
            payload.put("authorizationStatus", "NOT_APPLICABLE");
            payload.put("platformWrite", "NOT_PERFORMED");
            String title = text(candidate.get("title"));
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource
                    (tenant_id,resource_type,name,status,xianyu_account_id,xy_goods_id,stock,amount,data_json)
                    VALUES (?,?,?,1,?,?,1,?,?)
                    """, tenantId, resourceType, title.isBlank() ? resourceType + " QA 资源" : title,
                    number(run.get("xianyu_account_id")), blank(text(candidate.get("itemId"))),
                    decimal(candidate.get("price")), json(payload));
            Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbcTemplate.update("""
                    INSERT INTO merchant_resource_account(resource_id,tenant_id,xianyu_account_id) VALUES (?,?,?)
                    """, id, tenantId, number(run.get("xianyu_account_id")));
            String versionRequestId = "WFRES-" + runId + "-" + node.get("sequence_no") + "-" + index;
            String resourceFingerprint = fingerprint(payload);
            jdbcTemplate.update("""
                    INSERT INTO growth_resource_version
                    (tenant_id,resource_id,version_no,lifecycle_state,request_id,request_fingerprint,
                     payload_fingerprint,payload_json,source_type,source_url,source_item_id,source_captured_time,
                     authorization_status,license_type,license_note,supplier_name,activation_request_id,
                     activated_time,operator_username)
                    VALUES (?,?,1,'ACTIVE',?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),'qa-workflow')
                    """, tenantId, id, versionRequestId, resourceFingerprint, resourceFingerprint, json(payload),
                    "QA_FIXTURE", blank(text(candidate.get("sourceUrl"))), blank(text(candidate.get("itemId"))),
                    Timestamp.from(Instant.now()), "NOT_APPLICABLE",
                    "MATERIAL".equals(resourceType) ? "QA_FIXTURE" : null,
                    "QA Mock 派生资源，仅用于隔离验收", "SUPPLY".equals(resourceType) ? "QA Workflow Fixture" : null,
                    versionRequestId);
            ids.add(id);
        }
        return Map.of("resourceIds", ids, "count", ids.size(), "resourceType", resourceType,
                "platformWrite", "NOT_PERFORMED", "source", "QA_FIXTURE");
    }

    private Map<String, Object> previousOutputs(Long tenantId, Long runId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT node_type,output_json FROM growth_workflow_node_run
                 WHERE tenant_id=? AND workflow_run_id=? AND status='SUCCEEDED' ORDER BY sequence_no
                """, tenantId, runId);
        for (Map<String, Object> row : rows) result.put(text(row.get("node_type")), jsonMap(text(row.get("output_json"))));
        return result;
    }

    private List<Map<String, Object>> candidateList(Map<String, Object> previous) {
        for (String type : List.of("FILTER", "SEARCH")) {
            Map<String, Object> output = castMap(previous.get(type));
            Object items = output.get("items");
            if (items instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object value : list) result.add(map(value));
                return result;
            }
        }
        return List.of();
    }

    private void applyCancellation(Long runId, String requestId) {
        applyCancellationForTenant(tenant(), runId, requestId);
    }

    private void applyCancellationForTenant(Long tenantId, Long runId, String requestId) {
        String before = jdbcTemplate.queryForObject("SELECT status FROM growth_workflow_run WHERE tenant_id=? AND id=?",
                String.class, tenantId, runId);
        jdbcTemplate.update("""
                UPDATE growth_workflow_node_run SET status='CANCELLED',completed_time=NOW(3),
                       error_message='运行已在节点开始前安全取消'
                 WHERE tenant_id=? AND workflow_run_id=? AND status='PENDING'
                """, tenantId, runId);
        jdbcTemplate.update("""
                UPDATE growth_workflow_run SET status='CANCELLED',current_node_id=NULL,completed_time=NOW(3),
                       output_json=?,lock_version=lock_version+1 WHERE tenant_id=? AND id=?
                """, json(runSummary(tenantId, runId)), tenantId, runId);
        eventForTenant(tenantId, runId, null, "RUN_CANCELLED", before, "CANCELLED",
                "未开始节点已取消；已完成节点保留证据，可选择安全补偿", Map.of("platformWrite", "NOT_PERFORMED"), requestId);
    }

    private Map<String, Object> runSummary(Long tenantId, Long runId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN")) {
            counts.put(state, countNodes(tenantId, runId, state));
        }
        return Map.of("nodeCounts", counts, "platformWrite", "NOT_PERFORMED");
    }

    private int countNodes(Long tenantId, Long runId, String state) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM growth_workflow_node_run
                 WHERE tenant_id=? AND workflow_run_id=? AND status=?
                """, Integer.class, tenantId, runId, state);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> nodeRuns(Long runId) {
        return jdbcTemplate.query("""
                SELECT id,sequence_no,node_id,node_type,node_name,status,attempt_count,idempotency_key,
                       input_json,output_json,error_message,compensation_status,compensation_json,
                       started_time,completed_time,created_time,updated_time
                  FROM growth_workflow_node_run WHERE tenant_id=? AND workflow_run_id=? ORDER BY sequence_no
                """, (rs, rowNum) -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", rs.getLong("id"));
            node.put("sequence", rs.getInt("sequence_no"));
            node.put("nodeId", rs.getString("node_id"));
            node.put("nodeType", rs.getString("node_type"));
            node.put("nodeName", rs.getString("node_name"));
            node.put("status", rs.getString("status"));
            node.put("attemptCount", rs.getInt("attempt_count"));
            node.put("idempotencyKey", rs.getString("idempotency_key"));
            node.put("input", jsonMap(rs.getString("input_json")));
            node.put("output", jsonMap(rs.getString("output_json")));
            node.put("error", rs.getString("error_message"));
            node.put("compensationStatus", rs.getString("compensation_status"));
            node.put("compensation", jsonMap(rs.getString("compensation_json")));
            node.put("startedTime", instant(rs.getTimestamp("started_time")));
            node.put("completedTime", instant(rs.getTimestamp("completed_time")));
            node.put("createdTime", instant(rs.getTimestamp("created_time")));
            node.put("updatedTime", instant(rs.getTimestamp("updated_time")));
            return node;
        }, tenant(), runId);
    }

    private List<Map<String, Object>> events(Long runId) {
        return jdbcTemplate.query("""
                SELECT id,node_run_id,event_type,from_state,to_state,summary,evidence_json,request_id,
                       operator_user_id,operator_username,created_time
                  FROM growth_workflow_event WHERE tenant_id=? AND workflow_run_id=? ORDER BY id DESC
                """, (rs, rowNum) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", rs.getLong("id"));
            event.put("nodeRunId", nullableLong(rs.getObject("node_run_id")));
            event.put("eventType", rs.getString("event_type"));
            event.put("fromState", rs.getString("from_state"));
            event.put("toState", rs.getString("to_state"));
            event.put("summary", rs.getString("summary"));
            event.put("evidence", jsonMap(rs.getString("evidence_json")));
            event.put("requestId", rs.getString("request_id"));
            event.put("operatorUserId", nullableLong(rs.getObject("operator_user_id")));
            event.put("operatorUsername", rs.getString("operator_username"));
            event.put("createdTime", instant(rs.getTimestamp("created_time")));
            return event;
        }, tenant(), runId);
    }

    private void event(Long runId, Long nodeId, String eventType, String from, String to,
                       String summary, Object evidence, String requestId) {
        eventForTenant(tenant(), runId, nodeId, eventType, from, to, summary, evidence, requestId);
    }

    private void eventForTenant(Long tenantId, Long runId, Long nodeId, String eventType, String from, String to,
                                String summary, Object evidence, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO growth_workflow_event
                (tenant_id,workflow_run_id,node_run_id,event_type,from_state,to_state,summary,evidence_json,
                 request_id,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, tenantId, runId, nodeId, eventType, blank(from), to, summary, json(evidence), blank(requestId),
                UserContext.getUserId(), UserContext.getUsername());
    }

    private ActionGuard beginAction(Long runId, String actionType, Map<String, Object> request) {
        run(runId);
        String requestId = requestId(request);
        Map<String, Object> signature = map(request);
        signature.remove("requestId");
        signature.put("workflowRunId", runId);
        signature.put("actionType", actionType);
        String requestFingerprint = fingerprint(signature);
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT workflow_run_id,action_type,request_fingerprint,result_json
                  FROM growth_workflow_action_request
                 WHERE tenant_id=? AND request_id=? FOR UPDATE
                """, tenant(), requestId);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (!runId.equals(number(row.get("workflow_run_id")))
                    || !actionType.equals(text(row.get("action_type")))
                    || !requestFingerprint.equals(text(row.get("request_fingerprint")))) {
                throw new BusinessException(409, "requestId 已用于不同工作流动作，请生成新的请求 ID");
            }
            String saved = text(row.get("result_json"));
            if (saved.isBlank()) throw new BusinessException(409, "同一工作流动作正在处理中，请稍后按原 requestId 查询");
            Map<String, Object> replay = jsonMap(saved);
            replay.put("idempotentReplay", true);
            return new ActionGuard(requestId, requestFingerprint, replay);
        }
        jdbcTemplate.update("""
                INSERT INTO growth_workflow_action_request
                (tenant_id,workflow_run_id,request_id,action_type,request_fingerprint,request_json,
                 operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?)
                """, tenant(), runId, requestId, actionType, requestFingerprint, json(signature),
                UserContext.getUserId(), UserContext.getUsername());
        return new ActionGuard(requestId, requestFingerprint, null);
    }

    private void completeAction(ActionGuard guard, Map<String, Object> result) {
        jdbcTemplate.update("""
                UPDATE growth_workflow_action_request SET result_json=?,completed_time=NOW(3)
                 WHERE tenant_id=? AND request_id=? AND request_fingerprint=?
                """, json(result), tenant(), guard.requestId(), guard.fingerprint());
    }

    private Map<String, Object> workflow(Long workflowId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,name,status,xianyu_account_id,created_time,updated_time FROM merchant_resource
                 WHERE tenant_id=? AND id=? AND resource_type='WORKFLOW'
                """, tenant(), workflowId);
        if (rows.isEmpty()) throw new BusinessException(404, "工作流不存在");
        Map<String, Object> raw = rows.get(0);
        Long accountId = number(raw.get("xianyu_account_id"));
        accountAccessService.requireAccess(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", workflowId);
        result.put("name", raw.get("name"));
        result.put("status", intValue(raw.get("status"), 0));
        result.put("accountId", accountId);
        result.put("createdTime", instant(raw.get("created_time")));
        result.put("updatedTime", instant(raw.get("updated_time")));
        return result;
    }

    private Map<String, Object> version(Long workflowId, Integer requestedVersion, boolean activeByDefault) {
        List<Map<String, Object>> rows;
        if (requestedVersion != null) {
            rows = jdbcTemplate.queryForList("""
                    SELECT id FROM growth_workflow_version
                     WHERE tenant_id=? AND workflow_resource_id=? AND version_no=?
                    """, tenant(), workflowId, requestedVersion);
        } else {
            rows = jdbcTemplate.queryForList("""
                    SELECT id FROM growth_workflow_version
                     WHERE tenant_id=? AND workflow_resource_id=? AND lifecycle_state='ACTIVE'
                     ORDER BY version_no DESC LIMIT 1
                    """, tenant(), workflowId);
            if (rows.isEmpty() && !activeByDefault) {
                rows = jdbcTemplate.queryForList("""
                        SELECT id FROM growth_workflow_version
                         WHERE tenant_id=? AND workflow_resource_id=? ORDER BY version_no DESC LIMIT 1
                        """, tenant(), workflowId);
            }
        }
        if (rows.isEmpty()) throw new BusinessException(409, "工作流没有已发布版本，请先启用一个定义版本");
        return versionById(number(rows.get(0).get("id")));
    }

    private Map<String, Object> versionById(Long versionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,workflow_resource_id,version_no,lifecycle_state,request_fingerprint,definition_fingerprint,
                       definition_json,change_summary,activation_request_id,published_time
                  FROM growth_workflow_version WHERE tenant_id=? AND id=?
                """, tenant(), versionId);
        if (rows.isEmpty()) throw new BusinessException(404, "工作流版本不存在");
        Map<String, Object> raw = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", versionId);
        result.put("workflowId", number(raw.get("workflow_resource_id")));
        result.put("version", intValue(raw.get("version_no"), 0));
        result.put("lifecycleState", raw.get("lifecycle_state"));
        result.put("requestFingerprint", raw.get("request_fingerprint"));
        result.put("fingerprint", raw.get("definition_fingerprint"));
        result.put("definition", jsonMap(text(raw.get("definition_json"))));
        result.put("changeSummary", raw.get("change_summary"));
        result.put("activationRequestId", raw.get("activation_request_id"));
        result.put("publishedTime", instant(raw.get("published_time")));
        return result;
    }

    private void audit(Long targetId, Long accountId, String type, String requestId,
                       Object before, Object after, Object diff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule("GROWTH_WORKFLOW");
        log.setOperationDesc("工作流版本或运行状态已更新，逐节点证据可追溯");
        log.setOperationStatus(1);
        log.setTargetType(type.startsWith("WORKFLOW_RUN") ? "WORKFLOW_RUN" : "WORKFLOW");
        log.setTargetId(String.valueOf(targetId));
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL");
        log.setRequestParams(json(singleValue("before", before)));
        log.setResponseResult(json(singleValue("after", after)));
        log.setFieldDiffJson(json(diff));
        log.setCreateTime(System.currentTimeMillis());
        operationLogService.logRequired(log);
    }

    private String runMode(String value) {
        String mode = text(value).toUpperCase();
        if (mode.isBlank()) mode = "DRY_RUN";
        if (!RUN_MODES.contains(mode)) throw new BusinessException(409, "当前只开放 DRY_RUN 和 QA_MOCK，真实平台执行尚未适配");
        return mode;
    }

    private String requestId(Map<String, Object> request) {
        String value = text(request.get("requestId"));
        if (value.isBlank() || value.length() > 64) throw new BusinessException(400, "必须提供不超过64个字符的 requestId");
        return value;
    }

    private Long tenant() {
        Long tenant = TenantContext.get();
        if (tenant == null) tenant = UserContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("缺少租户上下文");
        return tenant;
    }

    private String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(value)).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("无法生成工作流指纹", e); }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("工作流证据无法序列化", e); }
    }

    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) { return value instanceof Map<?, ?> map ? map(map) : new LinkedHashMap<>(); }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::number).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null || text(value).isBlank() ? null : Long.parseLong(text(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Integer nullableInt(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value)); }
        catch (NumberFormatException e) { throw new BusinessException(400, "版本号必须是整数"); }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(text(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private BigDecimal decimal(Object value) {
        try { return value == null || text(value).isBlank() ? BigDecimal.ZERO : new BigDecimal(text(value)); }
        catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.atZone(ZoneId.systemDefault()).toInstant();
        return null;
    }

    private Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private Map<String, Object> singleValue(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
    private String trim(String value) { String s = value == null || value.isBlank() ? "未知错误" : value; return s.length() > 1000 ? s.substring(0, 1000) : s; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private Object value(Object value) { return value == null ? "" : value; }
    private record ActionGuard(String requestId, String fingerprint, Map<String, Object> replay) { }
    private static final class OutcomeUnknownException extends RuntimeException {
        private OutcomeUnknownException(String message) { super(message); }
    }
}
