package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.AccountCapabilityService;
import com.xianyusmart.service.AccountCommandCenterService;
import com.xianyusmart.service.OperationalIssueService;
import com.xianyusmart.service.ConversationAssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 多账号运营驾驶舱。 */
@RestController
@RequestMapping("/api/command-center")
public class CommandCenterController {

    private final AccountCommandCenterService commandCenterService;
    private final OperationalIssueService issueService;
    private final AccountCapabilityService capabilityService;
    private final ConversationAssignmentService conversationService;

    public CommandCenterController(AccountCommandCenterService commandCenterService,
                                   OperationalIssueService issueService,
                                   AccountCapabilityService capabilityService,
                                   ConversationAssignmentService conversationService) {
        this.commandCenterService = commandCenterService;
        this.issueService = issueService;
        this.capabilityService = capabilityService;
        this.conversationService = conversationService;
    }

    @GetMapping("/accounts")
    public ResultObject<List<Map<String, Object>>> accounts() {
        return ResultObject.success(commandCenterService.accounts());
    }

    @GetMapping("/issues")
    public ResultObject<List<Map<String, Object>>> issues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(issueService.list(status, severity, accountId, limit));
    }

    @PostMapping("/issues/refresh")
    public ResultObject<Integer> refreshIssues() {
        return ResultObject.success(issueService.refreshFromSources());
    }

    @PostMapping("/issues/{id}/transition")
    public ResultObject<Void> transition(@PathVariable @Positive Long id,
                                         @Valid @RequestBody TransitionRequest request) {
        issueService.transition(id, request.status(), request.note());
        return ResultObject.success(null);
    }

    @GetMapping("/capabilities")
    public ResultObject<List<Map<String, Object>>> capabilities() {
        return ResultObject.success(capabilityService.list());
    }

    @PostMapping("/capabilities/probe")
    public ResultObject<Integer> probeCapabilities() {
        return ResultObject.success(capabilityService.probe());
    }

    @PostMapping("/capabilities/override")
    public ResultObject<Void> overrideCapability(@Valid @RequestBody CapabilityOverrideRequest request) {
        capabilityService.override(request.accountId(), request.capabilityCode(), request.status(), request.detail());
        return ResultObject.success(null);
    }

    @GetMapping("/conversations")
    public ResultObject<List<Map<String, Object>>> conversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(conversationService.list(status, limit));
    }

    @PostMapping("/conversations/{id}")
    public ResultObject<Void> updateConversation(@PathVariable @Positive Long id,
                                                 @RequestBody ConversationUpdateRequest request) {
        conversationService.update(id, request.status(), request.priority(),
                Boolean.TRUE.equals(request.claim()), request.note());
        return ResultObject.success(null);
    }

    public record TransitionRequest(@NotBlank String status, @Size(max = 1000) String note) { }

    public record CapabilityOverrideRequest(@NotNull @Positive Long accountId,
                                            @NotBlank String capabilityCode,
                                            @NotBlank String status,
                                            @Size(max = 500) String detail) { }

    public record ConversationUpdateRequest(String status, String priority, Boolean claim,
                                            @Size(max = 1000) String note) { }
}
