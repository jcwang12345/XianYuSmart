package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

@Data
public class BackupModuleRespDTO {
    private String moduleKey;
    private String moduleName;
    private List<String> dependencies;
    private Long recordCount;
    private Long estimatedSizeBytes;
    private String scope;
    private Boolean containsSensitiveSecrets;
}
