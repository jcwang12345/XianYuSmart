package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

@Data
public class BackupImportReqDTO {
    private String jsonData;
    private List<String> modules;
    private String requestId;
    private String previewToken;
    private String confirmationText;
}
