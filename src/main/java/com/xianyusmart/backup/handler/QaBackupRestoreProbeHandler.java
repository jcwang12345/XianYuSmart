package com.xianyusmart.backup.handler;

import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated persistence probe for independent backup E2E acceptance. It never
 * reads or writes a product table and is absent unless explicitly enabled.
 */
@Component
@ConditionalOnProperty(name = "app.backup.qa-mock.enabled", havingValue = "true")
public class QaBackupRestoreProbeHandler implements DataBackupHandler {

    private final JdbcTemplate jdbcTemplate;
    private final long allowedTenantId;

    public QaBackupRestoreProbeHandler(JdbcTemplate jdbcTemplate,
                                       @Value("${app.backup.qa-mock.tenant-id:-1}") long allowedTenantId) {
        this.jdbcTemplate = jdbcTemplate;
        this.allowedTenantId = allowedTenantId;
    }

    @Override public String getModuleKey() { return "qaRestoreProbe"; }
    @Override public String getModuleName() { return "隔离 QA 恢复探针"; }

    @Override
    public Map<String, Object> exportData() {
        long tenantId = requireAllowedTenant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("probes", jdbcTemplate.queryForList("""
                SELECT probe_key probeKey,probe_value probeValue
                  FROM xianyu_backup_restore_probe WHERE tenant_id=? ORDER BY probe_key
                """, tenantId));
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        long tenantId = requireAllowedTenant();
        if (!(data.get("probes") instanceof List<?> probes)) return;
        for (Object value : probes) {
            try {
                if (!(value instanceof Map<?, ?> probe)) throw new IllegalArgumentException("探针格式错误");
                String key = text(probe.get("probeKey"));
                String probeValue = text(probe.get("probeValue"));
                if (key == null || probeValue == null) throw new IllegalArgumentException("探针键值不能为空");
                if ("__FAIL__".equals(probeValue)) throw new IllegalStateException("QA_INJECTED_RESTORE_FAILURE");
                jdbcTemplate.update("""
                        INSERT INTO xianyu_backup_restore_probe(tenant_id,probe_key,probe_value)
                        VALUES (?,?,?) ON DUPLICATE KEY UPDATE probe_value=VALUES(probe_value)
                        """, tenantId, key, probeValue);
            } catch (Exception e) {
                DataBackupHandler.recordImportError(context, getModuleKey(), e.getMessage());
            }
        }
    }

    private long requireAllowedTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId != allowedTenantId) {
            throw new BusinessException(404, "隔离 QA 恢复探针未对当前经营主体开放");
        }
        return tenantId;
    }

    private String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
