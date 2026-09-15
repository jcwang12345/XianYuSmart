package com.xianyusmart.service.bo;

import lombok.Data;

import java.util.List;

@Data
public class BackupModuleRespBO {
    private String moduleKey;
    private String moduleName;
    private List<String> dependencies;
    private Long recordCount;
    private Long estimatedSizeBytes;
    private String scope;
    private Boolean containsSensitiveSecrets;
}
