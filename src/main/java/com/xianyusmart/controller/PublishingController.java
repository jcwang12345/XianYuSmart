package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.MerchantOperationsService;
import com.xianyusmart.service.PublishCapabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 新版发布工作台接口；旧 merchant 接口继续兼容。 */
@RestController
@RequestMapping("/api/publishing")
public class PublishingController {

    private final PublishCapabilityService capabilityService;
    private final MerchantOperationsService operationsService;

    public PublishingController(PublishCapabilityService capabilityService,
                                MerchantOperationsService operationsService) {
        this.capabilityService = capabilityService;
        this.operationsService = operationsService;
    }

    @GetMapping("/accounts/{accountId}/capabilities")
    public ResultObject<Map<String, Object>> capabilities(@PathVariable Long accountId) {
        return ResultObject.success(capabilityService.capabilities(accountId));
    }

    @PostMapping("/preflight")
    public ResultObject<Map<String, Object>> preflight(@RequestBody Map<String, Object> request) {
        Map<String, Object> dryRun = new java.util.LinkedHashMap<>(request);
        dryRun.put("dryRun", true);
        return ResultObject.success(operationsService.createPublishPlan(dryRun));
    }

    @PostMapping("/execute")
    public ResultObject<Map<String, Object>> execute(@RequestBody Map<String, Object> request) {
        Map<String, Object> command = new java.util.LinkedHashMap<>(request);
        command.put("dryRun", false);
        return ResultObject.success(operationsService.createPublishPlan(command));
    }

    @GetMapping("/requests/{requestId}")
    public ResultObject<Map<String, Object>> requestStatus(@PathVariable String requestId) {
        return ResultObject.success(operationsService.publishRequestStatus(requestId));
    }
}
