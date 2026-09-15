package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.TotpService;
import com.xianyusmart.service.UserSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 两步验证和登录设备管理。 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final TotpService totpService;
    private final UserSessionService sessionService;
    private final AuthService authService;

    public SecurityController(TotpService totpService, UserSessionService sessionService, AuthService authService) {
        this.totpService = totpService;
        this.sessionService = sessionService;
        this.authService = authService;
    }

    @GetMapping("/2fa/status")
    public ResultObject<Map<String, Object>> status() {
        SysUser user = authService.getCurrentUser(UserContext.getUserId());
        return ResultObject.success(Map.of(
                "enabled", user != null && Integer.valueOf(1).equals(user.getTotpEnabled()),
                "recoveryCodeCount", totpService.recoveryCodeCount(user)));
    }

    @PostMapping("/2fa/begin")
    public ResultObject<Map<String, Object>> begin(@RequestBody(required = false) RequestMetadata request) {
        return ResultObject.success(totpService.begin(UserContext.getUserId(), request == null ? null : request.requestId()));
    }

    @PostMapping("/2fa/confirm")
    public ResultObject<List<String>> confirm(@Valid @RequestBody CodeRequest request) {
        return ResultObject.success(totpService.confirm(UserContext.getUserId(), request.code(), request.requestId()));
    }

    @PostMapping("/2fa/disable")
    public ResultObject<Void> disable(@Valid @RequestBody CodeRequest request) {
        totpService.disable(UserContext.getUserId(), request.code(), request.requestId());
        return ResultObject.success(null);
    }

    @GetMapping("/sessions")
    public ResultObject<List<Map<String, Object>>> sessions() {
        return ResultObject.success(sessionService.list());
    }

    @PostMapping("/sessions/{id}/revoke")
    public ResultObject<Void> revoke(@PathVariable @Positive Long id) {
        sessionService.revoke(id);
        return ResultObject.success(null);
    }

    @PostMapping("/sessions/revoke-others")
    public ResultObject<Map<String,Object>> revokeOthers(@Valid @RequestBody RevokeOthersRequest request) {
        int revoked = sessionService.revokeOthers(request.keepSessionId());
        return ResultObject.success(Map.of("revokedCount", revoked, "keptSessionId", request.keepSessionId()));
    }

    public record RequestMetadata(String requestId) { }
    public record CodeRequest(@NotBlank String code, String requestId) { }
    public record RevokeOthersRequest(@NotNull @Positive Long keepSessionId) { }
}
