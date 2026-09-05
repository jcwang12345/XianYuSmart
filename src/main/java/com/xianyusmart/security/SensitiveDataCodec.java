package com.xianyusmart.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM codec used for account credentials and persisted browser state. */
public final class SensitiveDataCodec {
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretKeySpec key;

    private SensitiveDataCodec() {
    }

    public static void configure(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("ACCOUNT_DATA_ENCRYPTION_KEY/JWT_SECRET must not be empty");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize account data encryption", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String encrypt(String value) {
        if (value == null || value.isEmpty() || isEncrypted(value)) {
            return value;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt account data", e);
        }
    }

    public static String decrypt(String value) {
        if (value == null || value.isEmpty() || !isEncrypted(value)) {
            return value; // 兼容读取 V18 之前的明文，后续写入会自动加密。
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("invalid encrypted payload");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt account data; verify ACCOUNT_DATA_ENCRYPTION_KEY", e);
        }
    }

    private static SecretKeySpec requireKey() {
        SecretKeySpec activeKey = key;
        if (activeKey == null) {
            throw new IllegalStateException("Account data encryption has not been initialized");
        }
        return activeKey;
    }
}
