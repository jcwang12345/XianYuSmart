package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.security.SensitiveDataCodec;
import com.xianyusmart.cache.CacheService;
import com.xianyusmart.cache.LocalMapCacheServiceImpl;
import com.xianyusmart.entity.XianyuOperationLog;
import org.springframework.test.util.ReflectionTestUtils;

class TotpServiceTest {

    @Test
    void base32RoundTripPreservesSecretBytes() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(secret, TotpService.base32Decode(TotpService.base32Encode(secret)));
    }

    @Test
    void pendingTotpSecretIsEncryptedBeforePersistence() {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        SysUserMapper mapper=mock(SysUserMapper.class);
        OperationLogService auditService=mock(OperationLogService.class);
        SysUser user=new SysUser();user.setId(1L);user.setUsername("owner");user.setTotpEnabled(0);
        when(mapper.selectOne(any())).thenReturn(user);
        new TotpService(mapper, mock(CacheService.class), auditService).begin(1L, "qa-2fa-begin");
        ArgumentCaptor<SysUser> captor=ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).updateById(captor.capture());
        assertTrue(SensitiveDataCodec.isEncrypted(captor.getValue().getTotpSecret()));
        ArgumentCaptor<XianyuOperationLog> audit=ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(auditService).logRequired(audit.capture());
        assertEquals("qa-2fa-begin", audit.getValue().getRequestId());
        assertFalse(audit.getValue().getFieldDiffJson().contains(SensitiveDataCodec.decrypt(captor.getValue().getTotpSecret())));
    }

    @Test
    void invalidCodesAreLimitedPerUserForTenMinuteWindow() {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        CacheService cache = new LocalMapCacheServiceImpl();
        SysUser user = new SysUser();
        user.setId(9L);
        user.setTotpEnabled(1);
        user.setTotpSecret(SensitiveDataCodec.encrypt(TotpService.base32Encode(new byte[20])));
        TotpService service = new TotpService(mock(SysUserMapper.class), cache, mock(OperationLogService.class));

        for (int i = 0; i < 6; i++) assertFalse(service.verifyForUser(user, "not-a-code"));

        assertEquals("5", String.valueOf(cache.get("totp_attempt:9")));
        assertTrue(cache.getExpire("totp_attempt:9") > 0);
    }

    @Test
    void loginVerificationDistinguishesInvalidFromRateLimited() {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        CacheService cache = new LocalMapCacheServiceImpl();
        SysUser user = enabledUser(10L);
        TotpService service = new TotpService(mock(SysUserMapper.class), cache,
                mock(OperationLogService.class));
        String secret = SensitiveDataCodec.decrypt(user.getTotpSecret());
        String validCode = ReflectionTestUtils.invokeMethod(service, "generate", secret,
                Instant.now().getEpochSecond() / 30);

        for (int i = 0; i < 4; i++) {
            assertEquals(TotpService.LoginVerificationStatus.INVALID,
                    service.verifyForLogin(user, "not-a-code"));
        }
        assertEquals(TotpService.LoginVerificationStatus.RATE_LIMITED,
                service.verifyForLogin(user, "not-a-code"));
        assertEquals(TotpService.LoginVerificationStatus.RATE_LIMITED,
                service.verifyForLogin(user, validCode));
    }

    @Test
    void recoveryCodeIsConsumedOnceAndReplayFails() throws Exception {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        SysUserMapper mapper = mock(SysUserMapper.class);
        SysUser user = enabledUser(11L);
        String recoveryCode = "ABCDE-12345";
        String normalized = recoveryCode.replace("-", "");
        user.setTotpRecoveryCodes(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8))));
        when(mapper.consumeRecoveryCodesIfUnchanged(11L, user.getTotpRecoveryCodes(), ""))
                .thenReturn(1);
        TotpService service = new TotpService(mapper, new LocalMapCacheServiceImpl(),
                mock(OperationLogService.class));

        assertEquals(TotpService.LoginVerificationStatus.SUCCESS,
                service.verifyForLogin(user, recoveryCode));
        assertEquals("", user.getTotpRecoveryCodes());
        assertEquals(TotpService.LoginVerificationStatus.INVALID,
                service.verifyForLogin(user, recoveryCode));
        verify(mapper).consumeRecoveryCodesIfUnchanged(eq(11L), any(), eq(""));
    }

    @Test
    void concurrentIndependentSnapshotsCanConsumeRecoveryCodeOnlyOnce() throws Exception {
        SensitiveDataCodec.configure("totp-test-encryption-key-with-at-least-32-bytes");
        SysUserMapper mapper = mock(SysUserMapper.class);
        String recoveryCode = "ABCDE-12345";
        String storedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(recoveryCode.replace("-", "").getBytes(StandardCharsets.UTF_8)));
        AtomicReference<String> persisted = new AtomicReference<>(storedHash);
        when(mapper.consumeRecoveryCodesIfUnchanged(12L, storedHash, ""))
                .thenAnswer(invocation -> persisted.compareAndSet(storedHash, "") ? 1 : 0);
        TotpService service = new TotpService(mapper, new LocalMapCacheServiceImpl(),
                mock(OperationLogService.class));
        SysUser firstSnapshot = enabledUser(12L);
        SysUser secondSnapshot = enabledUser(12L);
        firstSnapshot.setTotpRecoveryCodes(storedHash);
        secondSnapshot.setTotpRecoveryCodes(storedHash);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.verifyForLogin(firstSnapshot, recoveryCode);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.verifyForLogin(secondSnapshot, recoveryCode);
            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            List<TotpService.LoginVerificationStatus> results =
                    List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));

            assertEquals(1, results.stream()
                    .filter(status -> status == TotpService.LoginVerificationStatus.SUCCESS).count());
            assertEquals(1, results.stream()
                    .filter(status -> status == TotpService.LoginVerificationStatus.INVALID).count());
            assertEquals("", persisted.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private SysUser enabledUser(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTotpEnabled(1);
        user.setTotpSecret(SensitiveDataCodec.encrypt(TotpService.base32Encode(new byte[20])));
        return user;
    }
}
