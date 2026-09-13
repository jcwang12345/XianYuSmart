package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.MessageWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/message-workspace")
public class MessageWorkspaceController {
    private final MessageWorkspaceService service;

    public MessageWorkspaceController(MessageWorkspaceService service) { this.service = service; }

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

    public record ConversationCommand(Long accountId, String sessionId, String goodsId, Integer minutes) {}
}
