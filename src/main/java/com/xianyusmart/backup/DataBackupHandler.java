package com.xianyusmart.backup;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public interface DataBackupHandler {

    String getModuleKey();

    String getModuleName();

    default List<String> getDependencies() {
        return List.of();
    }

    /** Backup exports intentionally omit live credentials and delivery secrets. */
    default boolean containsSensitiveSecrets() {
        return false;
    }

    /**
     * Import handlers historically tolerated bad individual rows. Safe restore needs
     * those row failures to be visible to the orchestrator so the outer transaction
     * can roll back instead of reporting a partial import as successful.
     */
    @SuppressWarnings("unchecked")
    static void recordImportError(Map<String, Object> context, String moduleKey, String message) {
        if (context == null) return;
        List<String> errors = (List<String>) context.computeIfAbsent("_importErrors", key -> new ArrayList<String>());
        errors.add(moduleKey + ": " + (message == null || message.isBlank() ? "未知导入错误" : message));
    }

    Map<String, Object> exportData();

    default void importData(Map<String, Object> data) {
        importData(data, new java.util.LinkedHashMap<>());
    }

    void importData(Map<String, Object> data, Map<String, Object> context);
}
