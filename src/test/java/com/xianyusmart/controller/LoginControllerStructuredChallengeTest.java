package com.xianyusmart.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.LoginReqDTO;
import com.xianyusmart.controller.dto.LoginRespDTO;
import com.xianyusmart.exception.LoginOutcomeException;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.bo.LoginRespBO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginControllerStructuredChallengeTest {

    private AuthService auth;
    private HttpServletRequest http;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        http = mock(HttpServletRequest.class);
        controller = new LoginController();
        ReflectionTestUtils.setField(controller, "authService", auth);
        ReflectionTestUtils.setField(controller, "trustProxy", false);
        when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        when(http.getHeader("User-Agent")).thenReturn("qa-browser");
        when(auth.checkLoginAttempt("127.0.0.1")).thenReturn(true);
    }

    @ParameterizedTest
    @MethodSource("controlledOutcomes")
    void mapsStableLoginOutcomeWithoutCountingTotpAsIpFailure(
            LoginOutcomeException outcome, int code, String errorCode, boolean recordsIp) {
        when(auth.login(any())).thenThrow(outcome);

        ResultObject<LoginRespDTO> result = controller.login(request(), http);

        assertEquals(code, result.getCode());
        assertEquals(errorCode, result.getErrorCode());
        assertNull(result.getData());
        if (recordsIp) verify(auth).recordLoginFailure("127.0.0.1");
        else verify(auth, never()).recordLoginFailure("127.0.0.1");
        verify(auth, never()).clearLoginFailure("127.0.0.1");
    }

    @Test
    void ipRateLimitIsStructuredAndSkipsCredentialVerification() {
        when(auth.checkLoginAttempt("127.0.0.1")).thenReturn(false);

        ResultObject<LoginRespDTO> result = controller.login(request(), http);

        assertEquals(429, result.getCode());
        assertEquals("LOGIN_RATE_LIMITED", result.getErrorCode());
        verify(auth, never()).login(any());
        verify(auth, never()).recordLoginFailure(any());
    }

    @Test
    void finalSuccessAloneClearsIpFailures() {
        LoginRespBO response = new LoginRespBO();
        response.setToken("access-token");
        response.setRefreshToken("refresh-token");
        response.setUsername("owner");
        when(auth.login(any())).thenReturn(response);

        ResultObject<LoginRespDTO> result = controller.login(request(), http);

        assertEquals(200, result.getCode());
        assertNull(result.getErrorCode());
        verify(auth).clearLoginFailure("127.0.0.1");
        verify(auth, never()).recordLoginFailure(any());
    }

    @Test
    void controlledFailureLogOmitsCredentialsSecondFactorAndIp() {
        LoginReqDTO request = request();
        request.setTotpCode("ABCDE-12345");
        when(auth.login(any())).thenThrow(LoginOutcomeException.invalidCredentials());
        Logger logger = (Logger) LoggerFactory.getLogger(LoginController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.login(request, http);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(logs.contains("owner"));
        assertFalse(logs.contains("127.0.0.1"));
        assertFalse(logs.contains("correct-password"));
        assertFalse(logs.contains("ABCDE-12345"));
    }

    private static Stream<Arguments> controlledOutcomes() {
        return Stream.of(
                Arguments.of(LoginOutcomeException.invalidCredentials(), 401,
                        "INVALID_CREDENTIALS", true),
                Arguments.of(LoginOutcomeException.totpRequired(), 428,
                        "TOTP_REQUIRED", false),
                Arguments.of(LoginOutcomeException.totpInvalid(), 400,
                        "TOTP_INVALID", false),
                Arguments.of(LoginOutcomeException.totpRateLimited(), 429,
                        "TOTP_RATE_LIMITED", false));
    }

    private LoginReqDTO request() {
        LoginReqDTO request = new LoginReqDTO();
        request.setUsername("owner");
        request.setPassword("correct-password");
        return request;
    }
}
