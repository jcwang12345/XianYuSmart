package com.xianyusmart.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常拦截器
 * 捕获所有异常并返回统一格式的错误信息
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusinessException(BusinessException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", e.getCode());
        result.put("message", e.getMessage());
        return result;
    }

    /** 用户提交的枚举、边界和格式错误统一按 400 返回，不能伪装成系统故障。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> handleIllegalArgumentException(IllegalArgumentException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", e.getMessage());
        return result;
    }

    /** Bean Validation 失败属于请求输入错误，返回首个可执行的字段提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, Object> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Map<String, Object> result = new HashMap<>();
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "请求参数校验失败" : error.getDefaultMessage())
                .orElse("请求参数校验失败");
        result.put("code", 400);
        result.put("message", message);
        return result;
    }

    /** JSON 结构或日期格式不合法属于用户输入错误，不应伪装成 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Map<String, Object> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", "请求参数格式错误，请检查日期时间和字段类型");
        return result;
    }
    
    /**
     * 处理验证码异常
     */
    @ExceptionHandler(CaptchaRequiredException.class)
    public Map<String, Object> handleCaptchaRequiredException(CaptchaRequiredException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 401);
        result.put("message", e.getMessage());
        result.put("captchaUrl", e.getCaptchaUrl());
        return result;
    }

    /**
     * 处理图片请求超过上传限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Map<String, Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 413);
        result.put("message", "图片大小不能超过10MB");
        return result;
    }
    
    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleException(Exception e) {
        log.error("未处理的系统异常", e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "系统异常，请稍后重试");
        return result;
    }
}
