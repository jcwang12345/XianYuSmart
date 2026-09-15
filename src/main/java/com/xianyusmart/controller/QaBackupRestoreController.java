package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.DataBackupService;
import com.xianyusmart.service.bo.BackupExportReqBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Safe local fixture that prepares a backup whose execute and rollback are observable. */
@RestController
@RequestMapping("/api/qa/backup")
@ConditionalOnProperty(name = "app.backup.qa-mock.enabled", havingValue = "true")
public class QaBackupRestoreController {

    private final JdbcTemplate jdbcTemplate;
    private final DataBackupService dataBackupService;
    private final long allowedTenantId;

    public QaBackupRestoreController(JdbcTemplate jdbcTemplate,
                                     DataBackupService dataBackupService,
                                     @Value("${app.backup.qa-mock.tenant-id:-1}") long allowedTenantId) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataBackupService = dataBackupService;
        this.allowedTenantId = allowedTenantId;
    }

    @PostMapping("/fixture")
    public ResultObject<Map<String, Object>> prepareFixture(@RequestBody(required = false) Map<String, Object> request) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId != allowedTenantId) {
            throw new BusinessException(404, "隔离 QA 恢复夹具未对当前经营主体开放");
        }
        String key = "SAFE-E2E";
        boolean injectFailure = request != null && "FAIL".equalsIgnoreCase(String.valueOf(request.get("mode")));
        String backupValue = injectFailure ? "__FAIL__" : "BACKUP_VALUE";
        jdbcTemplate.update("""
                INSERT INTO xianyu_backup_restore_probe(tenant_id,probe_key,probe_value)
                VALUES (?,?,?) ON DUPLICATE KEY UPDATE probe_value=VALUES(probe_value)
                """, tenantId, key, backupValue);
        BackupExportReqBO exportRequest = new BackupExportReqBO();
        exportRequest.setModules(List.of("qaRestoreProbe"));
        String jsonData = dataBackupService.exportData(exportRequest).getJsonData();
        jdbcTemplate.update("""
                UPDATE xianyu_backup_restore_probe SET probe_value='RESTORE_POINT_VALUE'
                 WHERE tenant_id=? AND probe_key=?
                """, tenantId, key);
        return ResultObject.success(Map.of(
                "module", "qaRestoreProbe",
                "probeKey", key,
                "jsonData", jsonData,
                "currentValue", "RESTORE_POINT_VALUE",
                "faultMode", injectFailure ? "FAIL" : "SUCCESS",
                "expectedAfterExecute", injectFailure ? "RESTORE_POINT_VALUE (transaction rollback)" : "BACKUP_VALUE",
                "expectedAfterRollback", "RESTORE_POINT_VALUE",
                "externalPlatformWrite", false));
    }

    @GetMapping("/probe")
    public ResultObject<Map<String, Object>> probeState() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId != allowedTenantId) {
            throw new BusinessException(404, "隔离 QA 恢复夹具未对当前经营主体开放");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT probe_key probeKey,probe_value probeValue,updated_time updatedTime
                  FROM xianyu_backup_restore_probe WHERE tenant_id=? ORDER BY probe_key
                """, tenantId);
        return ResultObject.success(Map.of("records", rows, "externalPlatformWrite", false));
    }
}
