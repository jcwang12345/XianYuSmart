package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.MessageWorkspaceService;
import com.xianyusmart.service.AiHandoffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/message-workspace")
public class MessageWorkspaceController {
    private final MessageWorkspaceService service;
    private final AiHandoffService handoffService;

    public MessageWorkspaceController(MessageWorkspaceService service, AiHandoffService handoffService) {
        this.service = service;
        this.handoffService = handoffService;
    }

    @GetMapping("/conversations")
    public ResultObject<Map<String, Object>> conversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean pinnedOnly,
            @RequestParam(required = false) String keywordFlag,
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(service.conversations(status, unreadOnly, accountId, search,
                pinnedOnly, keywordFlag, limit));
    }

    @GetMapping("/conversation")
    public ResultObject<Map<String, Object>> detail(@RequestParam Long accountId,
                                                    @RequestParam String sessionId,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Integer offset) {
        return ResultObject.success(service.detail(accountId, sessionId, limit, offset));
    }

    @PostMapping("/conversation/read")
    public ResultObject<Void> read(@RequestBody ConversationCommand command) {
        service.markRead(command.accountId(), command.sessionId());
        return ResultObject.success(null);
    }

    @PostMapping("/conversation/takeover")
    public ResultObject<Map<String, Object>> takeover(@RequestBody ConversationCommand command) {
        return ResultObject.success(service.takeover(command.accountId(), command.sessionId(),
                command.goodsId(), command.minutes()));
    }

    @PostMapping("/conversation/update")
    public ResultObject<Map<String,Object>> update(@RequestBody MessageWorkspaceService.ConversationUpdate command){
        return ResultObject.success(service.updateConversation(command));
    }

    @PostMapping("/send/text")
    public ResultObject<Map<String, Object>> sendText(@RequestBody MessageWorkspaceService.SendCommand command) {
        return ResultObject.success(service.sendText(command));
    }

    @PostMapping("/send/image")
    public ResultObject<Map<String, Object>> sendImage(@RequestBody MessageWorkspaceService.SendCommand command) {
        return ResultObject.success(service.sendImage(command));
    }

    @GetMapping("/send-attempts/{requestId}")
    public ResultObject<Map<String,Object>> sendAttempt(@PathVariable String requestId,
                                                        @RequestParam Long accountId) {
        return ResultObject.success(service.sendAttempt(accountId, requestId));
    }

    @PostMapping("/send-attempts/{requestId}/resolution/preview")
    public ResultObject<Map<String,Object>> previewResolution(
            @PathVariable String requestId,
            @RequestBody MessageWorkspaceService.SendResolutionCommand command) {
        return ResultObject.success(service.previewResolution(requestId, command));
    }

    @PostMapping("/send-attempts/{requestId}/resolution")
    public ResultObject<Map<String,Object>> resolveAttempt(
            @PathVariable String requestId,
            @RequestBody MessageWorkspaceService.SendResolutionCommand command) {
        return ResultObject.success(service.resolveAttempt(requestId, command));
    }

    @GetMapping("/handoffs")
    public ResultObject<Map<String, Object>> handoffs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(handoffService.list(status, accountId, search, limit));
    }

    @PostMapping("/handoffs/{id}/claim")
    public ResultObject<Map<String, Object>> claimHandoff(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @RequestBody AiHandoffService.ActionCommand command) {
        return ResultObject.success(handoffService.claim(id, command));
    }

    @PostMapping("/handoffs/{id}/resolve")
    public ResultObject<Map<String, Object>> resolveHandoff(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @RequestBody AiHandoffService.ActionCommand command) {
        return ResultObject.success(handoffService.resolve(id, command));
    }

    public record ConversationCommand(Long accountId, String sessionId, String goodsId, Integer minutes) {}
}
