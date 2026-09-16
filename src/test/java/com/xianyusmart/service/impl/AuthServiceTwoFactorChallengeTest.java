package com.xianyusmart.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xianyusmart.cache.CacheService;
import com.xianyusmart.cache.LocalMapCacheServiceImpl;
import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.LoginOutcomeException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.security.SensitiveDataCodec;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.PlatformPermissionService;
import com.xianyusmart.service.TotpService;
import com.xianyusmart.service.bo.LoginReqBO;
import com.xianyusmart.service.bo.LoginRespBO;
import com.xianyusmart.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTwoFactorChallengeTest {

    private SysUserMapper users;
    private SysLoginTokenMapper tokens;
    private JwtUtil jwt;
    private TotpService totp;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(SysUserMapper.class);
        tokens = mock(SysLoginTokenMapper.class);
        jwt = mock(JwtUtil.class);
        totp = mock(TotpService.class);
        service = new AuthServiceImpl();
        ReflectionTestUtils.setField(service, "sysUserMapper", users);
        ReflectionTestUtils.setField(service, "sysLoginTokenMapper", tokens);
        ReflectionTestUtils.setField(service, "jwtUtil", jwt);
        ReflectionTestUtils.setField(service, "cacheService", mock(CacheService.class));
        ReflectionTestUtils.setField(service, "permissionService", mock(PlatformPermissionService.class));
        ReflectionTestUtils.setField(service, "totpService", totp);
        ReflectionTestUtils.setField(service, "refreshExpiration", 2_592_000_000L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingSecondFactorProducesChallengeWithoutVerificationOrToken(String code) {
        SysUser user = user(true);
        when(users.selectOne(any())).thenReturn(user);

        LoginOutcomeException error = assertThrows(LoginOutcomeException.class,
                () -> service.login(request("correct-password", code)));

        assertEquals(LoginOutcomeException.ErrorCode.TOTP_REQUIRED, error.getErrorCode());
        assertEquals(428, error.getBusinessCode());
        verify(totp, never()).verifyForLogin(any(), any());
        verify(jwt, never()).generateToken(any(), any());
        verify(tokens, never()).insert(any());
    }

    @Test
    void enabledAndDisabledAccountsShareInvalidCredentialSemantics() {
        SysUser enabled = user(true);
        SysUser disabled = user(false);

        when(users.selectOne(any())).thenReturn(enabled, disabled, null);
        for (int i = 0; i < 3; i++) {
            LoginOutcomeException error = assertThrows(LoginOutcomeException.class,
                    () -> service.login(request("wrong-password", null)));
            assertEquals(LoginOutcomeException.ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
            assertEquals(401, error.getBusinessCode());
        }
        verify(totp, never()).verifyForLogin(any(), any());
    }

    @Test
    void invalidAndRateLimitedSecondFactorsAreDistinctAndNeverIssueTokens() {
        SysUser user = user(true);
        when(users.selectOne(any())).thenReturn(user);
        when(totp.verifyForLogin(user, "111111"))
                .thenReturn(TotpService.LoginVerificationStatus.INVALID);
        when(totp.verifyForLogin(user, "222222"))
                .thenReturn(TotpService.LoginVerificationStatus.RATE_LIMITED);

        LoginOutcomeException invalid = assertThrows(LoginOutcomeException.class,
                () -> service.login(request("correct-password", "111111")));
        LoginOutcomeException limited = assertThrows(LoginOutcomeException.class,
                () -> service.login(request("correct-password", "222222")));

        assertEquals(LoginOutcomeException.ErrorCode.TOTP_INVALID, invalid.getErrorCode());
        assertEquals(LoginOutcomeException.ErrorCode.TOTP_RATE_LIMITED, limited.getErrorCode());
        verify(jwt, never()).generateToken(any(), any());
        verify(tokens, never()).insert(any());
    }

    @Test
    void successfulPasswordLoginWithoutTwoFactorKeepsExistingResponseShape() {
        SysUser user = user(false);
        when(users.selectOne(any())).thenReturn(user);
        stubSuccessfulTokenPersistence();

        var response = service.login(request("correct-password", null));

        assertEquals("access-token", response.getToken());
        assertNotNull(response.getRefreshToken());
        assertEquals("owner", response.getUsername());
        verify(totp, never()).verifyForLogin(any(), any());
        verify(tokens).insert(any(SysLoginToken.class));
    }

    @Test
    void successfulSecondFactorIssuesSingleSession() {
        SysUser user = user(true);
        when(users.selectOne(any())).thenReturn(user);
        when(totp.verifyForLogin(user, "123456"))
                .thenReturn(TotpService.LoginVerificationStatus.SUCCESS);
        stubSuccessfulTokenPersistence();

        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        LoginRespBO response;
        try {
            response = service.login(request("correct-password", "123456"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals("access-token", response.getToken());
        verify(tokens).insert(any(SysLoginToken.class));
        verify(totp).verifyForLogin(user, "123456");
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(logs.contains("owner"));
        assertFalse(logs.contains("127.0.0.1"));
        assertFalse(logs.contains("correct-password"));
        assertFalse(logs.contains("123456"));
        assertFalse(logs.contains("access-token"));
    }

    @Test
    void sessionWriteFailureRollsBackRecoveryCodeConsumptionTransaction() throws Exception {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        SysUser user = user(true);
        user.setTotpSecret(SensitiveDataCodec.encrypt("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        String recoveryCode = "ABCDE-12345";
        String storedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(recoveryCode.replace("-", "").getBytes(StandardCharsets.UTF_8)));
        user.setTotpRecoveryCodes(storedHash);
        AtomicReference<String> persistedCodes = new AtomicReference<>(storedHash);
        when(users.selectOne(any())).thenReturn(user);
        when(users.consumeRecoveryCodesIfUnchanged(9L, storedHash, ""))
                .thenAnswer(invocation -> persistedCodes.compareAndSet(storedHash, "") ? 1 : 0);
        ReflectionTestUtils.setField(service, "totpService", new TotpService(
                users, new LocalMapCacheServiceImpl(), mock(OperationLogService.class)));
        when(jwt.generateToken(9L, "owner")).thenReturn("access-token");
        when(jwt.getExpiration()).thenReturn(60_000L);
        when(tokens.insert(any())).thenThrow(new IllegalStateException("session write failed"));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doAnswer(invocation -> {
            persistedCodes.set(storedHash);
            return null;
        }).when(transactionManager).rollback(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        AuthService transactionalService = (AuthService) proxyFactory.getProxy();

        assertThrows(IllegalStateException.class,
                () -> transactionalService.login(request("correct-password", recoveryCode)));

        assertEquals(storedHash, persistedCodes.get());
        verify(users).consumeRecoveryCodesIfUnchanged(9L, storedHash, "");
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    private void stubSuccessfulTokenPersistence() {
        when(jwt.generateToken(9L, "owner")).thenReturn("access-token");
        when(jwt.getExpiration()).thenReturn(60_000L);
        when(tokens.selectList(any())).thenReturn(List.of());
    }

    private SysUser user(boolean twoFactor) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("owner");
        user.setPassword(new BCryptPasswordEncoder().encode("correct-password"));
        user.setStatus(1);
        user.setTotpEnabled(twoFactor ? 1 : 0);
        return user;
    }

    private LoginReqBO request(String password, String code) {
        LoginReqBO request = new LoginReqBO();
        request.setUsername("owner");
        request.setPassword(password);
        request.setTotpCode(code);
        request.setIp("127.0.0.1");
        request.setDeviceId("qa-browser");
        return request;
    }
}
