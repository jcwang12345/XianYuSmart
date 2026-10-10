package com.xianyusmart.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fast dispatcher for interactive account batches; database claims keep it idempotent with the main scheduler. */
@Component
public class AccountBatchScheduler {
    private final MerchantOperationsService operations;
    @org.springframework.beans.factory.annotation.Autowired
    private BackgroundPlatformActivityPolicy backgroundPlatformActivityPolicy = new BackgroundPlatformActivityPolicy();

    public AccountBatchScheduler(MerchantOperationsService operations) {
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${app.account-batch.dispatch-delay-ms:1500}", initialDelayString = "${app.account-batch.initial-delay-ms:3000}")
    public void dispatch() {
        if (!backgroundPlatformActivityPolicy.enabled()) return;
        operations.processDueAccountTasks();
    }
}
