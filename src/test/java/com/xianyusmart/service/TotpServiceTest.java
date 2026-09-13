package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TotpServiceTest {

    @Test
    void base32RoundTripPreservesSecretBytes() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(secret, TotpService.base32Decode(TotpService.base32Encode(secret)));
    }
}
