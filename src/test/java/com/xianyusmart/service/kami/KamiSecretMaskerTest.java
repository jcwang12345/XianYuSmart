package com.xianyusmart.service.kami;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KamiSecretMaskerTest {
    @Test void masksNormalSecretAndKeepsOnlyShortSuffix() {
        String masked = KamiSecretMasker.mask("CARD-SECRET-12345678");
        assertEquals("••••5678", masked);
        assertFalse(masked.contains("SECRET"));
    }

    @Test void masksShortSecretWithoutReturningOriginal() {
        assertEquals("••••B", KamiSecretMasker.mask("AB"));
    }

    @Test void preservesNullAndEmpty() {
        assertEquals(null, KamiSecretMasker.mask(null));
        assertEquals("", KamiSecretMasker.mask(""));
    }
}
