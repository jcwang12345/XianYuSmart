package com.xianyusmart.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.NotificationInboxService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.bo.BackupExportReqBO;
import com.xianyusmart.service.bo.BackupImportReqBO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataBackupServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private DataBackupServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("flyway_schema_history")) return "48";
            if (sql.contains("LAST_INSERT_ID")) return 73L;
            return null;
        });
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        service = new DataBackupServiceImpl(
                List.of(new MemoryHandler("account", "闲鱼账号", List.of()),
                        new MemoryHandler("goods", "商品管理", List.of("account"))),
                objectMapper,
                jdbcTemplate,
                mock(TransactionTemplate.class),
                mock(OperationLogService.class),
                mock(NotificationInboxService.class));
        TenantContext.set(7L);
        UserContext.set(11L, "qa-owner", 7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportContainsTenantVersionCountsAndChecksumsWithoutClaimingEncryption() throws Exception {
        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("account", "goods"));

        Map<String, Object> root = objectMapper.readValue(service.exportData(request).getJsonData(), new TypeReference<>() {});
        Map<String, Object> manifest = (Map<String, Object>) root.get("manifest");
        List<Map<String, Object>> modules = (List<Map<String, Object>>) manifest.get("modules");

        assertEquals("2.0", manifest.get("formatVersion"));
        assertEquals(7, ((Number) manifest.get("tenantId")).intValue());
        assertEquals("48", manifest.get("schemaVersion"));
        assertEquals(false, manifest.get("encrypted"));
        assertEquals(2, modules.size());
        assertTrue(modules.stream().allMatch(module -> String.valueOf(module.get("checksum")).matches("[0-9a-f]{64}")));
        assertTrue(modules.stream().allMatch(module -> ((Number) module.get("recordCount")).longValue() == 1L));
    }

    @Test
    void previewRejectsLegacyBackupWithoutManifest() throws Exception {
        BackupImportReqBO request = restoreRequest(objectMapper.writeValueAsString(Map.of("modules", Map.of("account", Map.of()))), List.of("account"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.previewRestore(request));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("manifest"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewRejectsCrossTenantBackup() throws Exception {
        Map<String, Object> root = exportedRoot();
        ((Map<String, Object>) root.get("manifest")).put("tenantId", 88);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.previewRestore(restoreRequest(objectMapper.writeValueAsString(root), List.of("account", "goods"))));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("其他经营主体"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewRejectsPayloadOrModuleChecksumTampering() throws Exception {
        Map<String, Object> root = exportedRoot();
        Map<String, Object> modules = (Map<String, Object>) root.get("modules");
        ((Map<String, Object>) modules.get("account")).put("rows", List.of(Map.of("id", "tampered")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.previewRestore(restoreRequest(objectMapper.writeValueAsString(root), List.of("account", "goods"))));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("校验和"));
    }

    @Test
    void previewRejectsMissingDependency() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.previewRestore(restoreRequest(objectMapper.writeValueAsString(exportedRoot()), List.of("goods"))));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("依赖模块 account"));
    }

    @Test
    void previewPersistsReadOnlyJobAndReturnsExactConfirmation() throws Exception {
        Map<String, Object> response = service.previewRestore(
                restoreRequest(objectMapper.writeValueAsString(exportedRoot()), List.of("account", "goods")));

        assertEquals(73L, response.get("jobId"));
        assertEquals(false, response.get("writePerformed"));
        assertEquals("恢复 2 个模块", response.get("requiredConfirmation"));
        assertNotNull(response.get("previewToken"));
        assertEquals(true, response.get("executable"));
    }

    @Test
    void executeRejectsWrongStrongConfirmationBeforeAnyWrite() throws Exception {
        String json = objectMapper.writeValueAsString(exportedRoot());
        String sourceChecksum = sha256(json);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.contains("preview_token")) return List.of();
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", 73L);
            job.put("status", "PREVIEWED");
            job.put("previewToken", "preview-token");
            job.put("sourceChecksum", sourceChecksum);
            job.put("requiredConfirmation", "恢复 2 个模块");
            job.put("requestedBy", 11L);
            job.put("expiresTime", Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)));
            job.put("selectedModulesJson", "[\"account\",\"goods\"]");
            return List.of(job);
        });
        BackupImportReqBO request = restoreRequest(json, List.of("account", "goods"));
        request.setPreviewToken("preview-token");
        request.setConfirmationText("恢复2个模块");

        BusinessException error = assertThrows(BusinessException.class, () -> service.executeRestore(request));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("确认文本"));
    }

    @Test
    void rowLevelHandlerErrorsCannotBeReportedAsSuccessfulImport() {
        DataBackupHandler broken = new MemoryHandler("account", "闲鱼账号", List.of()) {
            @Override
            public void importData(Map<String, Object> data, Map<String, Object> context) {
                DataBackupHandler.recordImportError(context, getModuleKey(), "row failed");
            }
        };
        DataBackupServiceImpl importService = new DataBackupServiceImpl(List.of(broken), objectMapper, jdbcTemplate,
                mock(TransactionTemplate.class), mock(OperationLogService.class), mock(NotificationInboxService.class));
        BackupImportReqBO request = new BackupImportReqBO();
        request.setModules(List.of("account"));
        request.setJsonData("{\"modules\":{\"account\":{\"rows\":[]}}}");

        var response = importService.importData(request);

        assertEquals(0, response.getSuccessCount());
        assertEquals(List.of("account"), response.getFailedModules());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportedRoot() throws Exception {
        BackupExportReqBO request = new BackupExportReqBO();
        request.setModules(List.of("account", "goods"));
        return objectMapper.readValue(service.exportData(request).getJsonData(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private BackupImportReqBO restoreRequest(String json, List<String> modules) {
        BackupImportReqBO request = new BackupImportReqBO();
        request.setJsonData(json);
        request.setModules(modules);
        request.setRequestId("qa-backup-" + System.nanoTime());
        return request;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static class MemoryHandler implements DataBackupHandler {
        private final String key;
        private final String name;
        private final List<String> dependencies;

        private MemoryHandler(String key, String name, List<String> dependencies) {
            this.key = key;
            this.name = name;
            this.dependencies = dependencies;
        }

        @Override public String getModuleKey() { return key; }
        @Override public String getModuleName() { return name; }
        @Override public List<String> getDependencies() { return dependencies; }
        @Override public Map<String, Object> exportData() { return Map.of("rows", List.of(Map.of("id", key + "-1"))); }
        @Override public void importData(Map<String, Object> data, Map<String, Object> context) { }
    }
}
