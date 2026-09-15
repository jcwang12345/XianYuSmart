package com.xianyusmart.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fast dispatcher for interactive account batches; database claims keep it idempotent with the main scheduler. */
@Component
public class AccountBatchScheduler {
    private final MerchantOperationsService operations;

    public AccountBatchScheduler(MerchantOperationsService operations) {
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${app.account-batch.dispatch-delay-ms:1500}", initialDelayString = "${app.account-batch.initial-delay-ms:3000}")
    public void dispatch() {
        operations.processDueAccountTasks();
    }
}
