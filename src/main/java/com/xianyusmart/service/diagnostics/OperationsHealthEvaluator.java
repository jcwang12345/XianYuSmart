package com.xianyusmart.service.diagnostics;

/**
 * 运营健康状态汇总规则
 */
public final class OperationsHealthEvaluator {

    private OperationsHealthEvaluator() {
    }

    public static String overallStatus(long criticalCount, long warningCount) {
        return overallStatus(criticalCount, warningCount, 0);
    }

    public static String overallStatus(long criticalCount, long warningCount, long unknownCount) {
        if (criticalCount > 0) {
            return "CRITICAL";
        }
        if (warningCount > 0) {
            return "WARNING";
        }
        return unknownCount > 0 ? "UNKNOWN" : "HEALTHY";
    }
}
