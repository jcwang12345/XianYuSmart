package com.xianyusmart.service.kami;

import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 持久化外部供货配额与熔断策略；调用方必须在配置行锁事务内使用。 */
public final class ExternalSupplyPolicy {

    private ExternalSupplyPolicy() {
    }

    public static Admission admit(XianyuKamiConfig config, int quantity, LocalDateTime now) {
        if (quantity < 1) throw new BusinessException(400, "外部供货数量必须大于0");
        String state = normalizeState(config.getExternalCircuitState());
        int cooldown = positive(config.getExternalCooldownSeconds(), 300);
        if ("OPEN".equals(state)) {
            LocalDateTime openedAt = config.getExternalCircuitOpenedAt();
            if (openedAt == null || openedAt.plusSeconds(cooldown).isAfter(now)) {
                throw new BusinessException(409, "外部供货熔断中，请等待冷却或人工复核后重置");
            }
            state = "HALF_OPEN";
        } else if ("HALF_OPEN".equals(state)) {
            throw new BusinessException(409, "外部供货正在半开探测，暂不接受并发请求");
        }

        LocalDate today = now.toLocalDate();
        int used = today.equals(config.getExternalQuotaDate())
                ? Math.max(0, value(config.getExternalQuotaUsed())) : 0;
        Integer quota = config.getExternalDailyQuota();
        if (quota != null && quota > 0 && used + quantity > quota) {
            throw new BusinessException(409, "外部供货今日配额不足，需要人工补充或调整配额");
        }
        int usedAfter = used + quantity;
        config.setExternalQuotaDate(today);
        config.setExternalQuotaUsed(usedAfter);
        config.setExternalCircuitState(state);
        return new Admission(state, usedAfter);
    }

    public static void markSuccess(XianyuKamiConfig config) {
        config.setExternalCircuitState("CLOSED");
        config.setExternalConsecutiveFailures(0);
        config.setExternalCircuitOpenedAt(null);
    }

    public static Failure markFailure(XianyuKamiConfig config, boolean uncertain, LocalDateTime now) {
        int failures = Math.max(0, value(config.getExternalConsecutiveFailures())) + 1;
        int threshold = positive(config.getExternalFailureThreshold(), 3);
        boolean open = uncertain || failures >= threshold;
        config.setExternalConsecutiveFailures(failures);
        if (open) {
            config.setExternalCircuitState("OPEN");
            config.setExternalCircuitOpenedAt(now);
        } else {
            config.setExternalCircuitState("CLOSED");
        }
        LocalDateTime nextRetryTime = uncertain ? null : now.plusSeconds(Math.min(300, 15L * failures));
        return new Failure(config.getExternalCircuitState(), failures, nextRetryTime);
    }

    public static void reset(XianyuKamiConfig config) {
        markSuccess(config);
    }

    private static String normalizeState(String state) {
        if (state == null) return "CLOSED";
        String normalized = state.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "OPEN", "HALF_OPEN" -> normalized;
            default -> "CLOSED";
        };
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    public record Admission(String circuitState, int quotaUsedAfter) {
    }

    public record Failure(String circuitState, int consecutiveFailures, LocalDateTime nextRetryTime) {
    }
}
