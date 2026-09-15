package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class KamiConfigRespDTO {

    private Long id;

    private Long xianyuAccountId;

    private List<Long> xianyuAccountIds;

    private String sharingMode;

    private Long configVersion;

    private String aliasName;

    private String sourceType;

    private String externalApiUrl;

    private String externalApiHeaders;

    private Boolean externalApiHeadersConfigured;

    private String externalApiBody;

    private Boolean externalApiBodySensitiveConfigured;

    private String externalApiResultPath;

    private Integer externalApiTimeoutSeconds;

    private Integer externalDailyQuota;

    private Integer externalFailureThreshold;

    private Integer externalCooldownSeconds;

    private String externalCircuitState;

    private Integer externalConsecutiveFailures;

    private LocalDateTime externalCircuitOpenedAt;

    private Integer externalQuotaUsed;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;

    private Integer totalCount;

    private Integer usedCount;

    private Integer availableCount;

    private Integer reservedCount;

    private Integer reviewRequiredCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
