package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.MerchantTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ACC-10 cross-page account selection, preflight and persistent per-account tasks. */
@Service
public class AccountBatchService {
    private static final Set<String> OPERATIONS = Set.of("ENABLE", "DISABLE", "SYNC", "RENEW");
    private static final Set<String> SELECTION_MODES = Set.of("EXPLICIT", "FILTER_SNAPSHOT");
    private static final int MAX_ACCOUNTS = 1000;

    private final AccountMatrixService matrix;
    private final AccountAccessService access;
    private final AccountBatchExecutionService execution;
    private final MerchantTaskMapper tasks;
    private final OperationLogService operationLogs;
    private final ObjectMapper json;

    public AccountBatchService(AccountMatrixService matrix, AccountAccessService access,
                               AccountBatchExecutionService execution, MerchantTaskMapper tasks,
                               OperationLogService operationLogs, ObjectMapper json) {
        this.matrix = matrix;
        this.access = access;
        this.execution = execution;
        this.tasks = tasks;
        this.operationLogs = operationLogs;
        this.json = json;
    }

    public Preview preview(Request request) {
        Request normalized = normalize(request, false);
        List<Map<String, Object>> selected = resolve(normalized);
        Long tenantId = requireTenant();
        List<Candidate> candidates = new ArrayList<>();
        int conflicts = 0;
        int qaCount = 0;
        for (Map<String, Object> account : selected) {
            Long accountId = ((Number) account.get("accountId")).longValue();
            boolean qa = execution.isQaEligible(tenantId, accountId);
            if (qa) qaCount++;
            String conflict = conflict(normalized.operationType(), account);
            if (conflict != null) conflicts++;
            candidates.add(new Candidate(accountId, displayName(account), integer(account.get("accountStatus")),
                    text(account.get("connectionStatus")), text(account.get("authorizationStatus")),
                    conflict == null, conflict));
        }
        if (qaCount > 0 && qaCount < candidates.size()) {
            throw new BusinessException(400, "隔离 QA 账号不能与真实账号混合创建任务");
        }
        int executable = candidates.size() - conflicts;
        String channel = executable > 0 && qaCount == candidates.size() ? "QA_MOCK" : "LOCAL_RUNTIME";
        String token = sha256(normalized.operationType() + "|" + candidates.stream()
                .map(item -> item.accountId() + ":" + item.accountStatus() + ":" + item.connectionStatus() + ":" + item.executable())
                .toList());
        String confirmation = "确认对" + candidates.size() + "个账号执行" + label(normalized.operationType())
                + "，可执行" + executable + "个，冲突" + conflicts + "个"
                + ("QA_MOCK".equals(channel) ? "；隔离 QA Mock，不触达闲鱼平台" : "");
        String notice = "SYNC".equals(normalized.operationType())
                ? "当前同步范围为消息连接运行实况；店铺画像和处罚仍以各自平台快照及来源时间为准。"
                : "RENEW".equals(normalized.operationType())
                ? "续期会逐账号准备私密二维码，仍需使用对应闲鱼 App 扫码确认，系统不会代替安全验证。"
                : null;
        return new Preview(normalized.operationType(), normalized.selectionMode(), candidates.size(), conflicts,
                executable, confirmation, token, channel, notice, List.copyOf(candidates));
    }

    @Transactional
    public Map<String, Object> create(Request request) {
        Request normalized = normalize(request, true);
        Long tenantId = requireTenant();
        String batchId = "AB-" + sha256(normalized.requestId() + "|" + normalized.operationType())
                .substring(0, 20).toUpperCase(Locale.ROOT);
        List<MerchantTask> existing = tasks.selectByBatchId(tenantId, batchId);
        if (!existing.isEmpty()) {
            assertReplayMatches(existing.getFirst(), normalized);
            Map<String, Object> response = batch(batchId);
            response.put("idempotentReplay", true);
            return response;
        }
        Preview preview = preview(normalized);
        if (!preview.confirmationSummary().equals(normalized.confirmationText())) {
            throw new BusinessException(400, "确认范围已变化，请重新预检");
        }
        if (!preview.previewToken().equals(normalized.previewToken())) {
            throw new BusinessException(409, "账号范围或状态已变化，请重新预检");
        }
        if (preview.executableCount() == 0) throw new BusinessException(409, "所选账号全部存在冲突，无法创建任务");
        boolean replay = true;
        for (Candidate candidate : preview.items()) {
            if (!candidate.executable()) continue;
            String taskType = "ACCOUNT_" + normalized.operationType();
            String requestKey = sha256(normalized.requestId() + "|" + taskType + "|" + candidate.accountId());
            if (tasks.selectByRequestKey(tenantId, taskType, requestKey) != null) continue;
            MerchantTask task = new MerchantTask();
            task.setTenantId(tenantId);
            task.setTaskType(taskType);
            task.setRequestKey(requestKey);
            task.setBatchId(batchId);
            task.setXianyuAccountId(candidate.accountId());
            task.setStatus(0);
            task.setScheduledTime(LocalDateTime.now());
            task.setAttemptCount(0);
            task.setMaxAttempts(1);
            task.setRequestJson(write(Map.of("requestId", normalized.requestId(), "operationType", normalized.operationType(),
                    "selectionMode", normalized.selectionMode(), "previewToken", normalized.previewToken())));
            try {
                tasks.insert(task);
                replay = false;
            } catch (DuplicateKeyException ignored) {
                // A concurrent idempotent submit won. The same batch is returned below.
            }
        }
        if (!replay) audit(normalized.requestId(), batchId, preview);
        Map<String, Object> response = batch(batchId);
        response.put("idempotentReplay", replay);
        return response;
    }

    private void assertReplayMatches(MerchantTask task, Request request) {
        try {
            var stored = json.readTree(task.getRequestJson());
            if (!request.previewToken().equals(stored.path("previewToken").asText())
                    || !request.selectionMode().equals(stored.path("selectionMode").asText())
                    || !request.operationType().equals(stored.path("operationType").asText())) {
                throw new BusinessException(409, "同一 requestId 已用于不同的账号批量请求");
            }
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已有账号批量任务数据无法解析", error);
        }
    }

    public Map<String, Object> batch(String batchId) {
        if (batchId == null || !batchId.matches("AB-[A-F0-9]{20}")) throw new BusinessException(400, "批次ID无效");
        List<MerchantTask> items = tasks.selectByBatchId(requireTenant(), batchId);
        if (items.isEmpty()) throw new BusinessException(404, "账号批量任务不存在");
        int queued = 0, running = 0, succeeded = 0, failed = 0, cancelled = 0;
        for (MerchantTask task : items) {
            if (task.getStatus() == null || task.getStatus() == 0) queued++;
            else if (task.getStatus() == 1) running++;
            else if (task.getStatus() == 2) succeeded++;
            else if (task.getStatus() == -1 || task.getStatus() == 4) failed++;
            else if (task.getStatus() == 3) cancelled++;
        }
        String status = running > 0 ? "RUNNING" : queued > 0 ? "QUEUED" : failed > 0 && succeeded > 0 ? "PARTIAL_SUCCESS"
                : failed > 0 ? "FAILED" : cancelled == items.size() ? "CANCELLED" : "SUCCEEDED";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("operationType", items.getFirst().getTaskType().replace("ACCOUNT_", ""));
        result.put("status", status);
        result.put("totalCount", items.size());
        result.put("queuedCount", queued);
        result.put("runningCount", running);
        result.put("succeededCount", succeeded);
        result.put("failedCount", failed);
        result.put("cancelledCount", cancelled);
        result.put("items", items);
        return result;
    }

    private List<Map<String, Object>> resolve(Request request) {
        LinkedHashMap<Long, Map<String, Object>> selected = new LinkedHashMap<>();
        Set<Long> excluded = new LinkedHashSet<>(request.excludedAccountIds());
        if ("EXPLICIT".equals(request.selectionMode())) {
            if (request.accountIds().isEmpty()) throw new BusinessException(400, "请选择账号");
            for (Long accountId : new LinkedHashSet<>(request.accountIds())) {
                access.requireAccess(accountId);
                if (!excluded.contains(accountId)) selected.put(accountId, matrix.accountDetail(accountId));
            }
        } else {
            int page = 1;
            while (selected.size() <= MAX_ACCOUNTS) {
                AccountMatrixService.MatrixPage result = matrix.listAccounts(request.filter().search(),
                        request.filter().connectionStatus(), request.filter().riskSeverity(), request.filter().groupId(), page, 100);
                for (Map<String, Object> item : result.records()) {
                    Long accountId = ((Number) item.get("accountId")).longValue();
                    if (!excluded.contains(accountId)) selected.put(accountId, item);
                }
                if (page >= result.totalPages()) break;
                page++;
            }
        }
        if (selected.isEmpty()) throw new BusinessException(400, "当前范围没有可选账号");
        if (selected.size() > MAX_ACCOUNTS) throw new BusinessException(400, "单批最多处理1000个账号，请缩小范围");
        return List.copyOf(selected.values());
    }

    private Request normalize(Request request, boolean create) {
        if (request == null) throw new BusinessException(400, "批量请求不能为空");
        String operation = upper(request.operationType());
        String selection = upper(request.selectionMode());
        if (!OPERATIONS.contains(operation)) throw new BusinessException(400, "账号批量操作类型无效");
        if (!SELECTION_MODES.contains(selection)) throw new BusinessException(400, "账号选择模式无效");
        String requestId = text(request.requestId());
        if (requestId.isBlank() || requestId.length() > 80) throw new BusinessException(400, "requestId不能为空且最多80字符");
        if (create && text(request.previewToken()).isBlank()) throw new BusinessException(400, "请先完成范围预检");
        return new Request(requestId, operation, selection,
                request.accountIds() == null ? List.of() : request.accountIds(),
                request.excludedAccountIds() == null ? List.of() : request.excludedAccountIds(),
                request.filter() == null ? new Filter(null, null, null, null) : request.filter(),
                request.confirmationText(), request.previewToken());
    }

    private String conflict(String operation, Map<String, Object> account) {
        Integer status = integer(account.get("accountStatus"));
        if ("ENABLE".equals(operation) && Integer.valueOf(1).equals(status)) return "账号已经启用";
        if ("DISABLE".equals(operation) && Integer.valueOf(0).equals(status)) return "账号已经停用";
        if (("SYNC".equals(operation) || "RENEW".equals(operation)) && Integer.valueOf(0).equals(status)) return "账号已停用";
        return null;
    }

    private void audit(String requestId, String batchId, Preview preview) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperatorUserId(UserContext.getUserId());
        log.setOperatorUsername(UserContext.getUsername());
        log.setOperationType(OperationConstants.Type.UPDATE);
        log.setOperationModule(OperationConstants.Module.ACCOUNT);
        log.setOperationDesc("创建账号批量任务 · " + label(preview.operationType()));
        log.setOperationStatus(OperationConstants.Status.SUCCESS);
        log.setTargetType(OperationConstants.TargetType.TASK);
        log.setTargetId(batchId);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId + "|" + preview.operationType());
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("QA_MOCK".equals(preview.executionChannel()) ? "QA_FIXTURE" : "LOCAL");
        log.setRequestParams(write(Map.of("operationType", preview.operationType(), "selectedCount", preview.selectedCount())));
        log.setResponseResult(write(Map.of("batchId", batchId, "executableCount", preview.executableCount(), "conflictCount", preview.conflictCount())));
        operationLogs.logRequired(log);
    }

    private Long requireTenant() {
        Long tenant = TenantContext.get();
        if (tenant == null) throw new BusinessException(401, "租户上下文缺失");
        return tenant;
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("批量任务序列化失败", e); } }
    private static String upper(String value) { return text(value).toUpperCase(Locale.ROOT); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private static String displayName(Map<String, Object> account) { String note=text(account.get("accountNote")); return note.isBlank()?"账号 "+account.get("accountId"):note; }
    private static String label(String operation) { return Map.of("ENABLE","启用","DISABLE","停用","SYNC","同步运行状态","RENEW","准备扫码续期").getOrDefault(operation, operation); }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }

    public record Filter(String search, String connectionStatus, String riskSeverity, Long groupId) {}
    public record Request(String requestId, String operationType, String selectionMode, List<Long> accountIds,
                          List<Long> excludedAccountIds, Filter filter, String confirmationText, String previewToken) {}
    public record Candidate(Long accountId, String accountName, Integer accountStatus, String connectionStatus,
                            String authorizationStatus, boolean executable, String conflictMessage) {}
    public record Preview(String operationType, String selectionMode, int selectedCount, int conflictCount,
                          int executableCount, String confirmationSummary, String previewToken,
                          String executionChannel, String executionNotice, List<Candidate> items) {}
}
