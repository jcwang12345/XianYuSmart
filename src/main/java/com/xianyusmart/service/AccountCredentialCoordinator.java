package com.xianyusmart.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A single account-level monitor shared by cookie, token and login refresh flows.
 * The deployment remains single-instance, so a JVM lock plus DB optimistic version is sufficient for V1.
 */
@Component
public class AccountCredentialCoordinator {
    private final ConcurrentHashMap<Long, Object> accountLocks = new ConcurrentHashMap<>();

    public Object lockFor(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        return accountLocks.computeIfAbsent(accountId, ignored -> new Object());
    }
}
