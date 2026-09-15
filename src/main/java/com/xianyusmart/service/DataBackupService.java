package com.xianyusmart.service;

import com.xianyusmart.service.bo.*;

import java.util.List;
import java.util.Map;

public interface DataBackupService {

    List<BackupModuleRespBO> getModules();

    BackupExportRespBO exportData(BackupExportReqBO reqBO);

    BackupImportRespBO importData(BackupImportReqBO reqBO);

    Map<String, Object> previewRestore(BackupImportReqBO reqBO);

    Map<String, Object> executeRestore(BackupImportReqBO reqBO);

    Map<String, Object> rollbackRestore(Long jobId, String requestId, String confirmationText);

    Map<String, Object> getRestoreJob(Long jobId);
}
