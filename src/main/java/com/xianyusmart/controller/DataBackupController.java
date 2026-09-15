package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.service.DataBackupService;
import com.xianyusmart.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/backup")
public class DataBackupController {

    @Autowired
    private DataBackupService dataBackupService;

    @PostMapping("/modules")
    public ResultObject<List<BackupModuleRespDTO>> getModules() {
        List<BackupModuleRespBO> boList = dataBackupService.getModules();
        List<BackupModuleRespDTO> result = new ArrayList<>();
        for (BackupModuleRespBO bo : boList) {
            BackupModuleRespDTO dto = new BackupModuleRespDTO();
            dto.setModuleKey(bo.getModuleKey());
            dto.setModuleName(bo.getModuleName());
            dto.setDependencies(bo.getDependencies());
            dto.setRecordCount(bo.getRecordCount());
            dto.setEstimatedSizeBytes(bo.getEstimatedSizeBytes());
            dto.setScope(bo.getScope());
            dto.setContainsSensitiveSecrets(bo.getContainsSensitiveSecrets());
            result.add(dto);
        }
        return ResultObject.success(result);
    }

    @PostMapping("/export")
    public ResultObject<BackupExportRespDTO> exportData(@RequestBody BackupExportReqDTO reqDTO) {
        BackupExportReqBO reqBO = new BackupExportReqBO();
        reqBO.setModules(reqDTO == null ? null : reqDTO.getModules());
        BackupExportRespBO respBO = dataBackupService.exportData(reqBO);
        BackupExportRespDTO respDTO = new BackupExportRespDTO();
        respDTO.setJsonData(respBO.getJsonData());
        return ResultObject.success(respDTO);
    }

    @PostMapping("/import")
    public ResultObject<BackupImportRespDTO> importData(@RequestBody BackupImportReqDTO reqDTO) {
        return ResultObject.validateFailed("直接导入已停用，请先调用恢复预检并使用预检令牌执行");
    }

    @PostMapping("/restore/preview")
    public ResultObject<java.util.Map<String, Object>> previewRestore(@RequestBody BackupImportReqDTO request) {
        return ResultObject.success(dataBackupService.previewRestore(toImportBO(request)));
    }

    @PostMapping("/restore/execute")
    public ResultObject<java.util.Map<String, Object>> executeRestore(@RequestBody BackupImportReqDTO request) {
        return ResultObject.success(dataBackupService.executeRestore(toImportBO(request)));
    }

    @GetMapping("/restore/jobs/{jobId}")
    public ResultObject<java.util.Map<String, Object>> restoreJob(@PathVariable Long jobId) {
        return ResultObject.success(dataBackupService.getRestoreJob(jobId));
    }

    @PostMapping("/restore/jobs/{jobId}/rollback")
    public ResultObject<java.util.Map<String, Object>> rollbackRestore(@PathVariable Long jobId,
                                                                       @RequestBody BackupImportReqDTO request) {
        return ResultObject.success(dataBackupService.rollbackRestore(jobId, request.getRequestId(), request.getConfirmationText()));
    }

    private BackupImportReqBO toImportBO(BackupImportReqDTO request) {
        BackupImportReqBO value = new BackupImportReqBO();
        if (request != null) {
            value.setJsonData(request.getJsonData());
            value.setModules(request.getModules());
            value.setRequestId(request.getRequestId());
            value.setPreviewToken(request.getPreviewToken());
            value.setConfirmationText(request.getConfirmationText());
        }
        return value;
    }

    @GetMapping("/log-dates")
    public ResultObject<List<String>> getLogDates() {
        try {
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                return ResultObject.success(new ArrayList<>());
            }

            File[] dirs = logDir.toFile().listFiles(File::isDirectory);
            if (dirs == null || dirs.length == 0) {
                return ResultObject.success(new ArrayList<>());
            }

            List<String> dates = new ArrayList<>();
            for (File dir : dirs) {
                String name = dir.getName();
                if (name.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    dates.add(name);
                }
            }

            dates.sort(Comparator.reverseOrder());
            return ResultObject.success(dates);
        } catch (Exception e) {
            log.error("获取日志日期列表失败", e);
            return ResultObject.failed("获取日志日期列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/log-download")
    public ResponseEntity<Resource> downloadLog(@RequestParam("date") String date) {
        try {
            LocalDate.parse(date, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            Path logsRoot = Paths.get("logs").toAbsolutePath().normalize();
            Path logDir = logsRoot.resolve(date).normalize();
            if (!logDir.startsWith(logsRoot)) {
                return ResponseEntity.badRequest().build();
            }
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return ResponseEntity.notFound().build();
            }

            File[] logFiles = logDir.toFile().listFiles((dir, name) -> name.endsWith(".log"));
            if (logFiles == null || logFiles.length == 0) {
                return ResponseEntity.notFound().build();
            }

            Path zipPath = logsRoot.resolve(date + ".zip").normalize();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                for (File logFile : logFiles) {
                    ZipEntry entry = new ZipEntry(logFile.getName());
                    zos.putNextEntry(entry);
                    try (FileInputStream fis = new FileInputStream(logFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }

            Resource resource = new FileSystemResource(zipPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + date + ".zip\"")
                    .body(resource);
        } catch (Exception e) {
            log.error("下载日志文件失败: date={}", date, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
