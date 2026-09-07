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

    private String aliasName;

    private String sourceType;

    private String externalApiUrl;

    private String externalApiHeaders;

    private Boolean externalApiHeadersConfigured;

    private String externalApiBody;

    private String externalApiResultPath;

    private Integer externalApiTimeoutSeconds;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;

    private Integer totalCount;

    private Integer usedCount;

    private Integer availableCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
