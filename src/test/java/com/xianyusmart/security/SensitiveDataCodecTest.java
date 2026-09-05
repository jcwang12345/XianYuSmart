package com.xianyusmart.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataCodecTest {

    @BeforeEach
    void configureKey() {
        SensitiveDataCodec.configure("test-account-data-key-that-is-not-production");
    }

    @Test
    void encryptsWithRandomIvAndRoundTrips() {
        String plaintext = "unb=123; _m_h5_tk=secret_token";
        String first = SensitiveDataCodec.encrypt(plaintext);
        String second = SensitiveDataCodec.encrypt(plaintext);

        assertTrue(SensitiveDataCodec.isEncrypted(first));
        assertNotEquals(plaintext, first);
        assertNotEquals(first, second);
        assertEquals(plaintext, SensitiveDataCodec.decrypt(first));
        assertEquals(plaintext, SensitiveDataCodec.decrypt(second));
    }

    @Test
    void readsLegacyPlaintextForOnlineMigration() {
        assertEquals("legacy-cookie", SensitiveDataCodec.decrypt("legacy-cookie"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = SensitiveDataCodec.encrypt("sensitive");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> SensitiveDataCodec.decrypt(tampered));
    }
}
