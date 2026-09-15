package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.MerchantOperationsService;
import com.xianyusmart.service.ListingDraftService;
import com.xianyusmart.service.PublishCapabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** 新版发布工作台接口；旧 merchant 接口继续兼容。 */
@RestController
@RequestMapping("/api/publishing")
public class PublishingController {

    private final PublishCapabilityService capabilityService;
    private final MerchantOperationsService operationsService;
    private final ListingDraftService listingDraftService;

    public PublishingController(PublishCapabilityService capabilityService,
                                MerchantOperationsService operationsService,
                                ListingDraftService listingDraftService) {
        this.capabilityService = capabilityService;
        this.operationsService = operationsService;
        this.listingDraftService = listingDraftService;
    }

    @GetMapping("/accounts/{accountId}/capabilities")
    public ResultObject<Map<String, Object>> capabilities(@PathVariable Long accountId) {
        return ResultObject.success(capabilityService.capabilities(accountId));
    }

    @GetMapping("/accounts/{accountId}/form-schema")
    public ResultObject<Map<String, Object>> formSchema(@PathVariable Long accountId,
                                                        @RequestParam(defaultValue = "VIRTUAL") String listingType) {
        return ResultObject.success(listingDraftService.formSchema(accountId, listingType));
    }

    @GetMapping("/accounts/{accountId}/drafts")
    public ResultObject<java.util.List<Map<String, Object>>> drafts(@PathVariable Long accountId) {
        return ResultObject.success(listingDraftService.list(accountId));
    }

    @GetMapping("/drafts/{id}")
    public ResultObject<Map<String, Object>> draft(@PathVariable Long id) {
        return ResultObject.success(listingDraftService.get(id));
    }

    @GetMapping("/drafts/{id}/versions")
    public ResultObject<java.util.List<Map<String, Object>>> draftVersions(@PathVariable Long id) {
        return ResultObject.success(listingDraftService.versions(id));
    }

    @PostMapping("/drafts")
    public ResultObject<Map<String, Object>> createDraft(@RequestBody Map<String, Object> request) {
        return ResultObject.success(listingDraftService.create(request));
    }

    @PutMapping("/drafts/{id}")
    public ResultObject<Map<String, Object>> updateDraft(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> request) {
        return ResultObject.success(listingDraftService.update(id, request));
    }

    @PostMapping("/validate")
    public ResultObject<Map<String, Object>> validateDraft(@RequestBody Map<String, Object> request) {
        return ResultObject.success(listingDraftService.validate(request));
    }

    @PostMapping("/preflight")
    public ResultObject<Map<String, Object>> preflight(@RequestBody Map<String, Object> request) {
        Map<String, Object> listingValidation = listingDraftService.requireValidForPublish(request);
        Map<String, Object> dryRun = new java.util.LinkedHashMap<>(request);
        dryRun.put("dryRun", true);
        Map<String, Object> result = new java.util.LinkedHashMap<>(operationsService.createPublishPlan(dryRun));
        result.put("listingValidation", listingValidation);
        result.put("platformDifferences", listingDraftService.platformDifferences(request, result));
        Map<String, Object> snapshot = listingDraftService.recordPreflight(request, listingValidation, result);
        result.putAll(snapshot);
        return ResultObject.success(result);
    }

    @PostMapping("/execute")
    public ResultObject<Map<String, Object>> execute(@RequestBody Map<String, Object> request) {
        Map<String, Object> listingValidation = listingDraftService.requireExecutable(request);
        Map<String, Object> preflightSnapshot = listingDraftService.consumePreflight(request);
        Map<String, Object> command = new java.util.LinkedHashMap<>(request);
        command.put("dryRun", false);
        Map<String, Object> result = new java.util.LinkedHashMap<>(operationsService.createPublishPlan(command));
        result.put("listingValidation", listingValidation);
        result.put("preflightSnapshotId", preflightSnapshot.get("id"));
        return ResultObject.success(result);
    }

    @GetMapping("/requests/{requestId}")
    public ResultObject<Map<String, Object>> requestStatus(@PathVariable String requestId) {
        return ResultObject.success(operationsService.publishRequestStatus(requestId));
    }
}
