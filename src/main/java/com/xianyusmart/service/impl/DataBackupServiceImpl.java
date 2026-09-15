package com.xianyusmart.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.DataBackupService;
import com.xianyusmart.service.NotificationInboxService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.bo.BackupExportReqBO;
import com.xianyusmart.service.bo.BackupExportRespBO;
import com.xianyusmart.service.bo.BackupImportReqBO;
import com.xianyusmart.service.bo.BackupImportRespBO;
import com.xianyusmart.service.bo.BackupModuleRespBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DataBackupServiceImpl implements DataBackupService {

    static final String FORMAT_VERSION = "2.0";
    static final int MAX_BACKUP_CHARS = 50_000_000;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Set<String> RESTORABLE_JOB_STATES = Set.of("SUCCEEDED", "FAILED");

    private final List<DataBackupHandler> handlers;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OperationLogService operationLogService;
    private final NotificationInboxService notificationInboxService;

    public DataBackupServiceImpl(List<DataBackupHandler> handlers,
                                 ObjectMapper objectMapper,
                                 JdbcTemplate jdbcTemplate,
                                 TransactionTemplate transactionTemplate,
                                 OperationLogService operationLogService,
                                 NotificationInboxService notificationInboxService) {
        this.handlers = handlers.stream().sorted(Comparator.comparingInt(item -> getImportPriority(item.getModuleKey()))).toList();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.operationLogService = operationLogService;
        this.notificationInboxService = notificationInboxService;
    }

    @Override
    public List<BackupModuleRespBO> getModules() {
        List<BackupModuleRespBO> result = new ArrayList<>();
        for (DataBackupHandler handler : handlers) {
            BackupModuleRespBO module = new BackupModuleRespBO();
            module.setModuleKey(handler.getModuleKey());
            module.setModuleName(handler.getModuleName());
            module.setDependencies(handler.getDependencies());
            module.setScope("当前经营主体");
            module.setContainsSensitiveSecrets(handler.containsSensitiveSecrets());
            try {
                Map<String, Object> snapshot = handler.exportData();
                module.setRecordCount(countRecords(snapshot));
                module.setEstimatedSizeBytes((long) jsonBytes(snapshot).length);
            } catch (Exception e) {
                log.warn("[DataBackup] 估算模块 {} 失败: {}", handler.getModuleKey(), e.getMessage());
                module.setRecordCount(null);
                module.setEstimatedSizeBytes(null);
            }
            result.add(module);
        }
        return result;
    }

    @Override
    public BackupExportRespBO exportData(BackupExportReqBO reqBO) {
        List<DataBackupHandler> selected = selectedHandlers(reqBO == null ? null : reqBO.getModules(), false);
        Map<String, Object> modulesData = new LinkedHashMap<>();
        List<Map<String, Object>> moduleManifest = new ArrayList<>();
        for (DataBackupHandler handler : selected) {
            Map<String, Object> moduleData = handler.exportData();
            byte[] moduleBytes = jsonBytes(moduleData);
            modulesData.put(handler.getModuleKey(), moduleData);
            moduleManifest.add(moduleDescriptor(handler, moduleData, moduleBytes));
            log.info("[DataBackup] 导出模块 {} 成功", handler.getModuleKey());
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("applicationVersion", applicationVersion());
        manifest.put("schemaVersion", schemaVersion());
        manifest.put("tenantId", tenant());
        manifest.put("exportedAt", LocalDateTime.now().format(FORMATTER));
        manifest.put("exportedBy", UserContext.getUserId());
        manifest.put("exportedByUsername", UserContext.getUsername());
        manifest.put("encrypted", false);
        manifest.put("containsSensitiveSecrets", selected.stream().anyMatch(DataBackupHandler::containsSensitiveSecrets));
        manifest.put("modules", moduleManifest);
        manifest.put("payloadChecksum", sha256(jsonBytes(modulesData)));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifest", manifest);
        root.put("modules", modulesData);
        BackupExportRespBO response = new BackupExportRespBO();
        response.setJsonData(json(root));
        safeAudit("BACKUP_EXPORT", "导出备份", 1, null, null,
                Map.of("modules", selected.stream().map(DataBackupHandler::getModuleKey).toList()),
                Map.of("payloadChecksum", manifest.get("payloadChecksum"), "moduleCount", selected.size()));
        return response;
    }

    @Override
    public BackupImportRespBO importData(BackupImportReqBO reqBO) {
        ParsedBackup parsed = parse(reqBO == null ? null : reqBO.getJsonData());
        List<DataBackupHandler> selected = selectedHandlers(reqBO == null ? null : reqBO.getModules(), false);
        ImportResult result = performImport(parsed.modules(), selected);
        BackupImportRespBO response = new BackupImportRespBO();
        response.setTotalCount(selected.size());
        response.setSuccessCount(result.successCount());
        response.setFailedModules(result.failedModules());
        return response;
    }

    @Override
    public Map<String, Object> previewRestore(BackupImportReqBO reqBO) {
        requireRequest(reqBO, true, false);
        ParsedBackup parsed = parse(reqBO.getJsonData());
        validateManifest(parsed);
        List<DataBackupHandler> selected = selectedHandlers(reqBO.getModules(), true);
        validateIncomingModulesAndDependencies(parsed, selected);
        String sourceChecksum = sha256(reqBO.getJsonData().getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (DataBackupHandler handler : selected) {
            Map<String, Object> incoming = asMap(parsed.modules().get(handler.getModuleKey()), "模块数据格式错误: " + handler.getModuleKey());
            Map<String, Object> current = handler.exportData();
            long incomingCount = countRecords(incoming);
            long currentCount = countRecords(current);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("moduleKey", handler.getModuleKey());
            comparison.put("moduleName", handler.getModuleName());
            comparison.put("dependencies", handler.getDependencies());
            comparison.put("incomingCount", incomingCount);
            comparison.put("currentCount", currentCount);
            comparison.put("potentialCreateCount", Math.max(0, incomingCount - currentCount));
            comparison.put("potentialOverwriteCount", Math.min(incomingCount, currentCount));
            comparison.put("estimated", true);
            comparison.put("incomingSizeBytes", jsonBytes(incoming).length);
            comparisons.add(comparison);
        }

        String previewRequestId = requireId(reqBO.getRequestId(), "预检requestId");
        List<String> selectedKeys = selected.stream().map(DataBackupHandler::getModuleKey).toList();
        Map<String, Object> existing = findJobBy("preview_request_id", previewRequestId);
        if (existing != null) {
            if (!Objects.equals(existing.get("sourceChecksum"), sourceChecksum)
                    || !Objects.equals(readStringList(String.valueOf(existing.get("selectedModulesJson"))), selectedKeys)) {
                throw new BusinessException(409, "相同requestId对应不同恢复范围或备份文件");
            }
            return jobResponse(existing, true);
        }

        String previewToken = UUID.randomUUID().toString();
        String requiredConfirmation = "恢复 " + selected.size() + " 个模块";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(20);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("executable", true);
        preview.put("writePerformed", false);
        preview.put("scope", "当前经营主体 " + tenant());
        preview.put("modules", comparisons);
        preview.put("warnings", List.of(
                "这是安全合并预检；执行前会按模块创建恢复点",
                "潜在新增/覆盖数量为保守估算，执行仍受事务保护",
                "恢复点可还原已有记录；安全合并中新建的业务记录不会被回滚动作自动删除"));

        jdbcTemplate.update("""
                INSERT INTO xianyu_backup_restore_job
                (tenant_id,preview_request_id,preview_token,source_checksum,selected_modules_json,
                 manifest_json,preview_json,required_confirmation,status,requested_by,expires_time)
                VALUES (?,?,?,?,?,?,?,?,'PREVIEWED',?,?)
                """, tenant(), previewRequestId, previewToken, sourceChecksum,
                json(selectedKeys), json(parsed.manifest()),
                json(preview), requiredConfirmation, UserContext.getUserId(), Timestamp.valueOf(expiresAt));
        Long jobId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        preview.put("jobId", jobId);
        preview.put("previewToken", previewToken);
        preview.put("requiredConfirmation", requiredConfirmation);
        preview.put("expiresAt", expiresAt.format(FORMATTER));
        preview.put("manifest", publicManifest(parsed.manifest()));
        safeAudit("BACKUP_RESTORE_PREVIEW", "备份恢复预检", 1, previewRequestId, jobId,
                Map.of("modules", selectedKeys),
                Map.of("writePerformed", false, "sourceChecksum", sourceChecksum));
        return preview;
    }

    @Override
    public Map<String, Object> executeRestore(BackupImportReqBO reqBO) {
        requireRequest(reqBO, true, true);
        String executeRequestId = requireId(reqBO.getRequestId(), "执行requestId");
        String token = requireId(reqBO.getPreviewToken(), "预检令牌");
        Map<String, Object> job = findJobBy("preview_token", token);
        if (job == null) throw new BusinessException(404, "恢复预检不存在或不属于当前经营主体");
        String sourceChecksum = sha256(reqBO.getJsonData().getBytes(StandardCharsets.UTF_8));
        if (Objects.equals(job.get("executeRequestId"), executeRequestId)) {
            if (!Objects.equals(job.get("sourceChecksum"), sourceChecksum)
                    || !Objects.equals(job.get("requiredConfirmation"), reqBO.getConfirmationText())) {
                throw new BusinessException(409, "相同执行requestId对应不同恢复内容");
            }
            return jobResponse(job, true);
        }
        if (!"PREVIEWED".equals(job.get("status"))) throw new BusinessException(409, "恢复任务当前状态不可执行");
        if (!Objects.equals(number(job.get("requestedBy")), UserContext.getUserId())) throw new BusinessException(403, "只能执行本人创建的恢复预检");
        if (LocalDateTime.now().isAfter(timestamp(job.get("expiresTime")))) throw new BusinessException(409, "恢复预检已过期，请重新预检");
        if (!Objects.equals(job.get("requiredConfirmation"), reqBO.getConfirmationText())) throw new BusinessException(400, "确认文本不匹配");
        if (!Objects.equals(job.get("sourceChecksum"), sourceChecksum)) throw new BusinessException(409, "备份文件在预检后发生变化");

        ParsedBackup parsed = parse(reqBO.getJsonData());
        validateManifest(parsed);
        List<String> selectedKeys = readStringList(String.valueOf(job.get("selectedModulesJson")));
        List<DataBackupHandler> selected = selectedHandlers(selectedKeys, true);
        validateIncomingModulesAndDependencies(parsed, selected);
        Long jobId = number(job.get("id"));

        try {
            createRestorePoints(jobId, selected);
        } catch (Exception e) {
            recordRestoreFailure(jobId, executeRequestId, selectedKeys, e, false);
            if (e instanceof BusinessException businessException) throw businessException;
            throw new BusinessException(500, "创建逐模块恢复点失败，未写入业务数据", e);
        }
        int claimed = jdbcTemplate.update("""
                UPDATE xianyu_backup_restore_job
                   SET execute_request_id=?,status='RUNNING',executed_by=?,started_time=NOW(3),error_message=NULL
                 WHERE tenant_id=? AND id=? AND status='PREVIEWED'
                """, executeRequestId, UserContext.getUserId(), tenant(), jobId);
        if (claimed != 1) throw new BusinessException(409, "恢复任务已被其他请求领取，请刷新任务状态");
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ImportResult result = performImport(parsed.modules(), selected);
                if (!result.failedModules().isEmpty()) {
                    status.setRollbackOnly();
                    throw new BusinessException(500, "恢复失败模块: " + String.join(",", result.failedModules()));
                }
                jdbcTemplate.update("UPDATE xianyu_backup_restore_job SET status='SUCCEEDED',finished_time=NOW(3) WHERE tenant_id=? AND id=?",
                        tenant(), jobId);
            });
        } catch (Exception e) {
            recordRestoreFailure(jobId, executeRequestId, selectedKeys, e, true);
            if (e instanceof BusinessException businessException) throw businessException;
            throw new BusinessException(500, "备份恢复失败，写入已回滚", e);
        }
        safeAudit("BACKUP_RESTORE_EXECUTE", "执行备份恢复", 1, executeRequestId, jobId,
                Map.of("modules", selectedKeys), Map.of("status", "SUCCEEDED", "restorePointCount", selected.size()));
        safeNotification("BACKUP_RESTORE_SUCCEEDED", "备份恢复完成",
                "恢复任务 #" + jobId + " 已完成，可从任务详情查看恢复点。",
                Map.of("requestId", executeRequestId, "jobId", jobId, "targetRoute", "/settings?panel=backup&jobId=" + jobId));
        return getRestoreJob(jobId);
    }

    @Override
    public Map<String, Object> rollbackRestore(Long jobId, String requestId, String confirmationText) {
        String safeRequestId = requireId(requestId, "回滚requestId");
        Map<String, Object> job = findJob(jobId);
        if (job == null) throw new BusinessException(404, "恢复任务不存在");
        String expected = "回滚恢复任务 " + jobId;
        if ("ROLLED_BACK".equals(job.get("status")) && Objects.equals(job.get("rollbackRequestId"), safeRequestId)) {
            if (!expected.equals(confirmationText)) throw new BusinessException(409, "相同回滚requestId对应不同确认文本");
            return jobResponse(job, true);
        }
        if (!RESTORABLE_JOB_STATES.contains(String.valueOf(job.get("status")))) throw new BusinessException(409, "当前任务没有可用恢复点");
        if (!expected.equals(confirmationText)) throw new BusinessException(400, "确认文本不匹配");
        List<Map<String, Object>> points = jdbcTemplate.queryForList("""
                SELECT module_key moduleKey,snapshot_json snapshotJson,snapshot_checksum snapshotChecksum
                  FROM xianyu_backup_restore_point WHERE tenant_id=? AND job_id=? ORDER BY id
                """, tenant(), jobId);
        if (points.isEmpty()) throw new BusinessException(409, "恢复点不存在");
        Map<String, Object> modules = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> point : points) {
            String moduleKey = String.valueOf(point.get("moduleKey"));
            String snapshotJson = String.valueOf(point.get("snapshotJson"));
            if (!Objects.equals(point.get("snapshotChecksum"), sha256(snapshotJson.getBytes(StandardCharsets.UTF_8)))) {
                throw new BusinessException(409, "恢复点校验失败: " + moduleKey);
            }
            modules.put(moduleKey, readMap(snapshotJson));
            keys.add(moduleKey);
        }
        List<DataBackupHandler> selected = selectedHandlers(keys, false);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ImportResult result = performImport(modules, selected);
                if (!result.failedModules().isEmpty()) {
                    status.setRollbackOnly();
                    throw new BusinessException(500, "回滚失败模块: " + String.join(",", result.failedModules()));
                }
                jdbcTemplate.update("""
                        UPDATE xianyu_backup_restore_job SET status='ROLLED_BACK',rollback_request_id=?,rolled_back_by=?,rolled_back_time=NOW(3)
                         WHERE tenant_id=? AND id=?
                        """, safeRequestId, UserContext.getUserId(), tenant(), jobId);
            });
        } catch (Exception e) {
            safeAudit("BACKUP_RESTORE_ROLLBACK", "回滚备份恢复", 0, safeRequestId, jobId,
                    Map.of("modules", keys), Map.of("status", "FAILED", "error", limit(e.getMessage(), 1000)));
            if (e instanceof BusinessException businessException) throw businessException;
            throw new BusinessException(500, "恢复点回滚失败", e);
        }
        safeAudit("BACKUP_RESTORE_ROLLBACK", "回滚备份恢复", 1, safeRequestId, jobId,
                Map.of("modules", keys), Map.of("status", "ROLLED_BACK"));
        return getRestoreJob(jobId);
    }

    @Override
    public Map<String, Object> getRestoreJob(Long jobId) {
        Map<String, Object> job = findJob(jobId);
        if (job == null) throw new BusinessException(404, "恢复任务不存在");
        Map<String, Object> response = jobResponse(job, false);
        response.put("restorePoints", jdbcTemplate.queryForList("""
                SELECT module_key moduleKey,module_name moduleName,record_count recordCount,
                       snapshot_checksum snapshotChecksum,created_time createdTime
                  FROM xianyu_backup_restore_point WHERE tenant_id=? AND job_id=? ORDER BY id
                """, tenant(), jobId));
        return response;
    }

    private void createRestorePoints(Long jobId, List<DataBackupHandler> selected) {
        for (DataBackupHandler handler : selected) {
            Map<String, Object> snapshot = handler.exportData();
            String snapshotJson = json(snapshot);
            jdbcTemplate.update("""
                    INSERT IGNORE INTO xianyu_backup_restore_point
                    (tenant_id,job_id,module_key,module_name,snapshot_json,snapshot_checksum,record_count)
                    VALUES (?,?,?,?,?,?,?)
                    """, tenant(), jobId, handler.getModuleKey(), handler.getModuleName(), snapshotJson,
                    sha256(snapshotJson.getBytes(StandardCharsets.UTF_8)), countRecords(snapshot));
        }
    }

    private void recordRestoreFailure(Long jobId, String requestId, List<String> selectedKeys,
                                      Exception error, boolean writeWasAttempted) {
        String message = limit(error.getMessage(), 1000);
        jdbcTemplate.update("""
                UPDATE xianyu_backup_restore_job
                   SET execute_request_id=?,status='FAILED',finished_time=NOW(3),error_message=?
                 WHERE tenant_id=? AND id=? AND status IN ('PREVIEWED','RUNNING')
                """, requestId, message, tenant(), jobId);
        safeAudit("BACKUP_RESTORE_EXECUTE", "执行备份恢复", 0, requestId, jobId,
                Map.of("modules", selectedKeys), Map.of("status", "FAILED", "error", message,
                        "writeAttempted", writeWasAttempted));
        safeNotification("BACKUP_RESTORE_FAILED", "备份恢复失败",
                writeWasAttempted
                        ? "恢复任务 #" + jobId + " 已回滚本次写入，请检查错误后重新预检。"
                        : "恢复任务 #" + jobId + " 未能创建完整恢复点，未写入业务数据。",
                Map.of("requestId", requestId, "jobId", jobId,
                        "targetRoute", "/settings?panel=backup&jobId=" + jobId));
    }

    private ImportResult performImport(Map<String, Object> modules, List<DataBackupHandler> selected) {
        Map<String, Object> context = new LinkedHashMap<>();
        List<String> importErrors = new ArrayList<>();
        context.put("_importErrors", importErrors);
        List<String> failed = new ArrayList<>();
        int success = 0;
        for (DataBackupHandler handler : selected) {
            try {
                int errorCountBefore = importErrors.size();
                handler.importData(asMap(modules.get(handler.getModuleKey()), "模块数据格式错误: " + handler.getModuleKey()), context);
                if (importErrors.size() > errorCountBefore) {
                    failed.add(handler.getModuleKey());
                } else {
                    success++;
                }
            } catch (Exception e) {
                failed.add(handler.getModuleKey());
                log.error("[DataBackup] 导入模块 {} 失败", handler.getModuleKey(), e);
            }
        }
        return new ImportResult(success, failed);
    }

    private void validateManifest(ParsedBackup parsed) {
        Map<String, Object> manifest = parsed.manifest();
        if (manifest.isEmpty()) throw new BusinessException(400, "旧版备份缺少 manifest，请先用当前版本重新导出");
        if (!FORMAT_VERSION.equals(String.valueOf(manifest.get("formatVersion")))) throw new BusinessException(400, "不支持的备份格式版本");
        if (!Objects.equals(number(manifest.get("tenantId")), tenant())) throw new BusinessException(409, "备份文件属于其他经营主体");
        String expected = String.valueOf(manifest.get("payloadChecksum"));
        String actual = sha256(jsonBytes(parsed.modules()));
        if (!Objects.equals(expected, actual)) throw new BusinessException(409, "备份内容校验和不匹配，文件可能已损坏或被修改");
        Object descriptors = manifest.get("modules");
        if (!(descriptors instanceof List<?> list)) throw new BusinessException(400, "备份 manifest 缺少模块清单");
        for (Object item : list) {
            Map<String, Object> descriptor = asMap(item, "备份模块清单格式错误");
            String key = String.valueOf(descriptor.get("moduleKey"));
            if (!parsed.modules().containsKey(key)) throw new BusinessException(409, "manifest 模块缺失: " + key);
            String checksum = String.valueOf(descriptor.get("checksum"));
            if (!Objects.equals(checksum, sha256(jsonBytes(parsed.modules().get(key))))) {
                throw new BusinessException(409, "模块校验和不匹配: " + key);
            }
        }
    }

    private void validateIncomingModulesAndDependencies(ParsedBackup parsed, List<DataBackupHandler> selected) {
        Set<String> selectedKeys = selected.stream().map(DataBackupHandler::getModuleKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (DataBackupHandler handler : selected) {
            if (!parsed.modules().containsKey(handler.getModuleKey())) throw new BusinessException(400, "备份文件缺少模块: " + handler.getModuleName());
            for (String dependency : handler.getDependencies()) {
                if (!selectedKeys.contains(dependency)) throw new BusinessException(400, handler.getModuleName() + " 依赖模块 " + dependency + "，请一并选择");
            }
        }
    }

    private List<DataBackupHandler> selectedHandlers(List<String> requested, boolean requireExplicit) {
        Map<String, DataBackupHandler> byKey = new LinkedHashMap<>();
        handlers.forEach(handler -> byKey.put(handler.getModuleKey(), handler));
        List<String> keys = requested == null ? List.of() : requested.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        if (keys.isEmpty() && requireExplicit) throw new BusinessException(400, "请至少选择一个恢复模块");
        if (keys.isEmpty()) return handlers;
        List<DataBackupHandler> selected = new ArrayList<>();
        for (String key : keys) {
            DataBackupHandler handler = byKey.get(key);
            if (handler == null) throw new BusinessException(400, "未知备份模块: " + key);
            selected.add(handler);
        }
        selected.sort(Comparator.comparingInt(item -> getImportPriority(item.getModuleKey())));
        return selected;
    }

    private ParsedBackup parse(String jsonData) {
        if (jsonData == null || jsonData.isBlank()) throw new BusinessException(400, "备份数据不能为空");
        if (jsonData.length() > MAX_BACKUP_CHARS) throw new BusinessException(413, "备份文件超过 50 MB，请分模块处理");
        try {
            Map<String, Object> root = objectMapper.readValue(jsonData, new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Object> modules = asMap(root.get("modules"), "备份数据格式错误: 缺少 modules");
            Map<String, Object> manifest = root.get("manifest") == null ? Map.of() : asMap(root.get("manifest"), "备份 manifest 格式错误");
            return new ParsedBackup(manifest, modules);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "解析备份数据失败", e);
        }
    }

    private Map<String, Object> moduleDescriptor(DataBackupHandler handler, Map<String, Object> data, byte[] bytes) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("moduleKey", handler.getModuleKey());
        descriptor.put("moduleName", handler.getModuleName());
        descriptor.put("dependencies", handler.getDependencies());
        descriptor.put("recordCount", countRecords(data));
        descriptor.put("sizeBytes", bytes.length);
        descriptor.put("checksum", sha256(bytes));
        return descriptor;
    }

    private Map<String, Object> publicManifest(Map<String, Object> manifest) {
        Map<String, Object> result = new LinkedHashMap<>(manifest);
        result.remove("exportedByUsername");
        return result;
    }

    private Map<String, Object> findJob(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(jobSelect() + " WHERE tenant_id=? AND id=?", tenant(), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> findJobBy(String column, String value) {
        if (!Set.of("preview_request_id", "preview_token").contains(column)) throw new IllegalArgumentException("不支持的任务查询列");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(jobSelect() + " WHERE tenant_id=? AND " + column + "=?", tenant(), value);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String jobSelect() {
        return """
                SELECT id,preview_request_id previewRequestId,execute_request_id executeRequestId,
                       rollback_request_id rollbackRequestId,preview_token previewToken,source_checksum sourceChecksum,
                       selected_modules_json selectedModulesJson,manifest_json manifestJson,preview_json previewJson,
                       required_confirmation requiredConfirmation,status,requested_by requestedBy,executed_by executedBy,
                       rolled_back_by rolledBackBy,expires_time expiresTime,started_time startedTime,
                       finished_time finishedTime,rolled_back_time rolledBackTime,error_message errorMessage,
                       created_time createdTime,updated_time updatedTime
                  FROM xianyu_backup_restore_job
                """;
    }

    private Map<String, Object> jobResponse(Map<String, Object> job, boolean idempotentReplay) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.get("id"));
        response.put("status", job.get("status"));
        response.put("previewToken", job.get("previewToken"));
        response.put("requiredConfirmation", job.get("requiredConfirmation"));
        response.put("expiresAt", job.get("expiresTime"));
        response.put("selectedModules", readStringList(String.valueOf(job.get("selectedModulesJson"))));
        response.put("preview", readMap(String.valueOf(job.get("previewJson"))));
        response.put("manifest", publicManifest(readMap(String.valueOf(job.get("manifestJson")))));
        response.put("startedAt", job.get("startedTime"));
        response.put("finishedAt", job.get("finishedTime"));
        response.put("rolledBackAt", job.get("rolledBackTime"));
        response.put("errorMessage", job.get("errorMessage"));
        response.put("idempotentReplay", idempotentReplay);
        response.put("writePerformed", !"PREVIEWED".equals(job.get("status")));
        return response;
    }

    private void audit(String operationType, String description, int status, String requestId, Long jobId,
                       Object request, Object response) {
        XianyuOperationLog logEntry = new XianyuOperationLog();
        logEntry.setOperationType(operationType);
        logEntry.setOperationModule("备份与恢复");
        logEntry.setOperationDesc(description);
        logEntry.setOperationStatus(status);
        logEntry.setOutcomeState(status == 1 ? "LOCAL_SUCCESS" : "FAILED");
        logEntry.setDataSource("LOCAL");
        logEntry.setRequestId(requestId);
        logEntry.setTargetType("BACKUP_RESTORE_JOB");
        logEntry.setTargetId(jobId == null ? null : String.valueOf(jobId));
        logEntry.setRequestParams(json(request));
        logEntry.setResponseResult(json(response));
        logEntry.setCreateTime(System.currentTimeMillis());
        operationLogService.log(logEntry);
    }

    private void safeAudit(String operationType, String description, int status, String requestId, Long jobId,
                           Object request, Object response) {
        try {
            audit(operationType, description, status, requestId, jobId, request, response);
        } catch (Exception e) {
            log.error("[DataBackup] 写入审计失败, operation={}, jobId={}", operationType, jobId, e);
        }
    }

    private void safeNotification(String eventType, String title, String content, Map<String, Object> data) {
        try {
            notificationInboxService.record(eventType, null, title, content, data);
        } catch (Exception e) {
            log.error("[DataBackup] 写入站内通知失败, eventType={}", eventType, e);
        }
    }

    private void requireRequest(BackupImportReqBO request, boolean requireJson, boolean requireToken) {
        if (request == null) throw new BusinessException(400, "请求不能为空");
        if (requireJson && (request.getJsonData() == null || request.getJsonData().isBlank())) throw new BusinessException(400, "备份数据不能为空");
        if (requireToken && (request.getPreviewToken() == null || request.getPreviewToken().isBlank())) throw new BusinessException(400, "预检令牌不能为空");
    }

    private Long tenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String schemaVersion() {
        try {
            return jdbcTemplate.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", String.class);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String applicationVersion() {
        String version = DataBackupServiceImpl.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private int getImportPriority(String moduleKey) {
        return switch (moduleKey) {
            case "account" -> 0;
            case "goods" -> 1;
            case "kami" -> 2;
            case "autoDelivery" -> 3;
            case "autoReply" -> 4;
            default -> 5;
        };
    }

    private long countRecords(Object value) {
        if (value instanceof List<?> list) return list.size();
        if (value instanceof Map<?, ?> map) return map.values().stream().mapToLong(this::countRecords).sum();
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String message) {
        if (!(value instanceof Map<?, ?>)) throw new BusinessException(400, message);
        return (Map<String, Object>) value;
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "读取恢复任务数据失败", e);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "读取恢复任务模块失败", e);
        }
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new BusinessException(500, "序列化备份数据失败", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(500, "序列化备份数据失败", e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String requireId(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 80) throw new BusinessException(400, label + "无效");
        return value.trim();
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        return Long.parseLong(String.valueOf(value));
    }

    private LocalDateTime timestamp(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private String limit(String value, int max) {
        if (value == null) return "未知错误";
        return value.substring(0, Math.min(max, value.length()));
    }

    private record ParsedBackup(Map<String, Object> manifest, Map<String, Object> modules) {}
    private record ImportResult(int successCount, List<String> failedModules) {}
}
