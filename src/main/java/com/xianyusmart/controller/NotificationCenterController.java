package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.NotificationChannelReqDTO;
import com.xianyusmart.controller.dto.NotificationChannelRespDTO;
import com.xianyusmart.entity.XianyuNotificationLog;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.NotificationInboxService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知中心接口
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationCenterController {

    private final NotificationCenterService notificationCenterService;
    private final NotificationInboxService notificationInboxService;

    public NotificationCenterController(NotificationCenterService notificationCenterService,
                                        NotificationInboxService notificationInboxService) {
        this.notificationCenterService = notificationCenterService;
        this.notificationInboxService = notificationInboxService;
    }

    @GetMapping("/channels")
    public ResultObject<List<NotificationChannelRespDTO>> listChannels() {
        return ResultObject.success(notificationCenterService.listChannels());
    }

    @PostMapping("/channels")
    public ResultObject<NotificationChannelRespDTO> saveChannel(
            @Valid @RequestBody NotificationChannelReqDTO request) {
        try {
            return ResultObject.success(notificationCenterService.saveChannel(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @DeleteMapping("/channels/{id}")
    public ResultObject<Void> deleteChannel(@PathVariable Long id,@RequestParam String requestId) {
        try {
            notificationCenterService.deleteChannel(id,requestId);
            return ResultObject.success(null);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/channels/{id}/test")
    public ResultObject<Map<String, Object>> testChannel(@PathVariable Long id) {
        try {
            return ResultObject.success(notificationCenterService.testChannel(id));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/logs")
    public ResultObject<List<XianyuNotificationLog>> listLogs(
            @RequestParam(required = false) Integer limit) {
        return ResultObject.success(notificationCenterService.listLogs(limit));
    }

    @GetMapping("/inbox")
    public ResultObject<Map<String, Object>> inbox(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ResultObject.success(notificationInboxService.list(view, accountId, search, page, pageSize));
    }

    @PostMapping("/inbox/{id}/read")
    public ResultObject<Void> read(@PathVariable Long id) {
        notificationInboxService.markRead(id);
        return ResultObject.success(null);
    }

    @PostMapping("/inbox/{id}/handling")
    public ResultObject<Void> handle(@PathVariable Long id, @RequestBody HandlingRequest request) {
        notificationInboxService.handle(id, request.status(), request.note());
        return ResultObject.success(null);
    }

    public record HandlingRequest(String status, String note) {}
}
