package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.security.SensitiveDataCodec;

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
        SysUser user=new SysUser();user.setId(1L);user.setUsername("owner");user.setTotpEnabled(0);
        when(mapper.selectOne(any())).thenReturn(user);
        new TotpService(mapper).begin(1L);
        ArgumentCaptor<SysUser> captor=ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).updateById(captor.capture());
        assertTrue(SensitiveDataCodec.isEncrypted(captor.getValue().getTotpSecret()));
    }
}
