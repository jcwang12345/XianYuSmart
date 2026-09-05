package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountCredentialCoordinatorTest {

    @Test
    void sharesOneMonitorPerAccountButNotAcrossAccounts() {
        AccountCredentialCoordinator coordinator = new AccountCredentialCoordinator();
        Object first = coordinator.lockFor(1L);
        assertSame(first, coordinator.lockFor(1L));
        assertNotSame(first, coordinator.lockFor(2L));
        assertThrows(IllegalArgumentException.class, () -> coordinator.lockFor(null));
    }
}
