package com.xianyusmart.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountDataEncryptionConfiguration {
    private final String encryptionKey;

    public AccountDataEncryptionConfiguration(@Value("${app.security.account-data-key}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    @PostConstruct
    void initialize() {
        SensitiveDataCodec.configure(encryptionKey);
    }
}
