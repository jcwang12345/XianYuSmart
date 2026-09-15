package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
}
