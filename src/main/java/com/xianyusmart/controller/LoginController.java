package com.xianyusmart.controller;

import com.xianyusmart.annotation.NoAuth;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.CheckUserExistsRespDTO;
import com.xianyusmart.controller.dto.LoginReqDTO;
import com.xianyusmart.controller.dto.LoginRespDTO;
import com.xianyusmart.controller.dto.RegisterReqDTO;
import com.xianyusmart.exception.LoginOutcomeException;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.bo.*;
import com.xianyusmart.util.RegistrationPasswordPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 登录控制器
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/login")
@NoAuth
public class LoginController {

    @Autowired
    private AuthService authService;

    @Value("${app.security.trust-proxy:false}")
    private boolean trustProxy;

    /**
     * 检查是否已有用户
     */
    @PostMapping("/checkUserExists")
    public ResultObject<CheckUserExistsRespDTO> checkUserExists() {
        try {
            CheckUserExistsRespBO respBO = authService.checkUserExists();
            CheckUserExistsRespDTO respDTO = new CheckUserExistsRespDTO();
            respDTO.setExists(respBO.getExists());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("检查用户存在失败", e);
            return ResultObject.failed("检查用户存在失败: " + e.getMessage());
        }
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public ResultObject<LoginRespDTO> register(@RequestBody RegisterReqDTO reqDTO, HttpServletRequest request) {
        try {
            // 参数校验
            if (reqDTO.getUsername() == null || reqDTO.getUsername().trim().isEmpty()) {
                return ResultObject.validateFailed("用户名不能为空");
            }
            if (reqDTO.getPassword() == null || reqDTO.getPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("密码不能为空");
            }
            if (reqDTO.getConfirmPassword() == null || !reqDTO.getPassword().equals(reqDTO.getConfirmPassword())) {
                return ResultObject.validateFailed("两次密码不一致");
            }
            if (reqDTO.getUsername().length() < 3 || reqDTO.getUsername().length() > 20) {
                return ResultObject.validateFailed("用户名长度需在3-20之间");
            }
            String passwordError = RegistrationPasswordPolicy.validate(reqDTO.getUsername(), reqDTO.getPassword());
            if (passwordError != null) {
                return ResultObject.validateFailed(passwordError);
            }

            RegisterReqBO reqBO = new RegisterReqBO();
            reqBO.setUsername(reqDTO.getUsername().trim());
            reqBO.setPassword(reqDTO.getPassword());

            LoginRespBO respBO = authService.register(reqBO);

            LoginRespDTO respDTO = new LoginRespDTO();
            respDTO.setToken(respBO.getToken());
            respDTO.setRefreshToken(respBO.getRefreshToken());
            respDTO.setAccessTokenExpiresInMs(respBO.getAccessTokenExpiresInMs());
            respDTO.setRefreshTokenExpireTime(respBO.getRefreshTokenExpireTime());
            respDTO.setUsername(respBO.getUsername());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("注册失败", e);
            return ResultObject.failed(e.getMessage());
        }
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResultObject<LoginRespDTO> login(@RequestBody LoginReqDTO reqDTO, HttpServletRequest request) {
        String ip = getClientIp(request);
        try {
            // 检查登录错误次数限制
            if (!authService.checkLoginAttempt(ip)) {
                return ResultObject.failed(429, "登录尝试过多，请稍后重试",
                        LoginOutcomeException.ErrorCode.LOGIN_RATE_LIMITED.name());
            }

            // 缺失凭据与错误凭据保持同一外部语义，但空请求不消耗 IP 失败额度。
            if (reqDTO.getUsername() == null || reqDTO.getUsername().trim().isEmpty()) {
                return ResultObject.failed(401, "用户名或密码错误",
                        LoginOutcomeException.ErrorCode.INVALID_CREDENTIALS.name());
            }
            if (reqDTO.getPassword() == null || reqDTO.getPassword().trim().isEmpty()) {
                return ResultObject.failed(401, "用户名或密码错误",
                        LoginOutcomeException.ErrorCode.INVALID_CREDENTIALS.name());
            }

            String deviceId = request.getHeader("User-Agent");
            if (deviceId != null && deviceId.length() > 100) {
                deviceId = deviceId.substring(0, 100);
            }

            LoginReqBO reqBO = new LoginReqBO();
            reqBO.setUsername(reqDTO.getUsername().trim());
            reqBO.setPassword(reqDTO.getPassword());
            reqBO.setIp(ip);
            reqBO.setDeviceId(deviceId);
            reqBO.setTotpCode(reqDTO.getTotpCode());

            LoginRespBO respBO = authService.login(reqBO);

            // 登录成功，清除失败记录
            authService.clearLoginFailure(ip);

            LoginRespDTO respDTO = new LoginRespDTO();
            respDTO.setToken(respBO.getToken());
            respDTO.setRefreshToken(respBO.getRefreshToken());
            respDTO.setAccessTokenExpiresInMs(respBO.getAccessTokenExpiresInMs());
            respDTO.setRefreshTokenExpireTime(respBO.getRefreshTokenExpireTime());
            respDTO.setUsername(respBO.getUsername());
            return ResultObject.success(respDTO);
        } catch (LoginOutcomeException e) {
            if (e.shouldRecordIpFailure()) {
                authService.recordLoginFailure(ip);
            }
            log.warn("登录未完成: outcome={}", e.getErrorCode());
            return ResultObject.failed(e.getBusinessCode(), e.getMessage(), e.getErrorCode().name());
        } catch (Exception e) {
            log.error("登录处理异常: outcome=INTERNAL_ERROR, errorType={}",
                    e.getClass().getSimpleName());
            return ResultObject.failed("登录服务暂不可用，请稍后重试");
        }
    }

    @PostMapping("/refresh")
    public ResultObject<LoginRespDTO> refresh(@RequestBody RefreshRequest body, HttpServletRequest request) {
        try {
            String deviceId = request.getHeader("User-Agent");
            if (deviceId != null && deviceId.length() > 100) deviceId = deviceId.substring(0, 100);
            LoginRespBO result = authService.refresh(body == null ? null : body.refreshToken(), getClientIp(request), deviceId);
            LoginRespDTO response = new LoginRespDTO();
            response.setToken(result.getToken()); response.setRefreshToken(result.getRefreshToken());
            response.setAccessTokenExpiresInMs(result.getAccessTokenExpiresInMs());
            response.setRefreshTokenExpireTime(result.getRefreshTokenExpireTime()); response.setUsername(result.getUsername());
            return ResultObject.success(response);
        } catch (Exception e) {
            return ResultObject.failed(401, e.getMessage());
        }
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ResultObject<?> logout(HttpServletRequest request) {
        try {
            String token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            authService.logout(token);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            return ResultObject.failed("退出登录失败: " + e.getMessage());
        }
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = null;
        if (trustProxy) {
            ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public record RefreshRequest(String refreshToken) {}
}
