package com.xianyusmart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 在节点边界逐步推进工作流，便于安全取消和进程重启恢复。 */
@Service
public class GrowthWorkflowScheduler {

    private final GrowthWorkflowService workflowService;

    @Value("${app.growth-workflow.dispatch-enabled:true}")
    private boolean dispatchEnabled;

    public GrowthWorkflowScheduler(GrowthWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Scheduled(fixedDelayString = "${app.growth-workflow.dispatch-delay-ms:1500}",
            initialDelayString = "${app.growth-workflow.initial-delay-ms:20000}")
    public void dispatch() {
        if (dispatchEnabled) workflowService.processPendingRuns(20);
    }
}
