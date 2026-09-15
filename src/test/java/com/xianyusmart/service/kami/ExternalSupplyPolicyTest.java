package com.xianyusmart.service.kami;

import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSupplyPolicyTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);

    @Test
    void dailyQuotaIsResetByDateAndThenConsumed() {
        XianyuKamiConfig config = config();
        config.setExternalDailyQuota(5);
        config.setExternalQuotaDate(LocalDate.of(2026, 9, 14));
        config.setExternalQuotaUsed(5);

        ExternalSupplyPolicy.Admission admission = ExternalSupplyPolicy.admit(config, 2, now);

        assertEquals(2, admission.quotaUsedAfter());
        assertEquals(LocalDate.of(2026, 9, 15), config.getExternalQuotaDate());
    }

    @Test
    void dailyQuotaRejectsBeforeAnyExternalRequest() {
        XianyuKamiConfig config = config();
        config.setExternalDailyQuota(5);
        config.setExternalQuotaDate(now.toLocalDate());
        config.setExternalQuotaUsed(4);

        BusinessException error = assertThrows(BusinessException.class,
                () -> ExternalSupplyPolicy.admit(config, 2, now));

        assertEquals(409, error.getCode());
        assertEquals(4, config.getExternalQuotaUsed());
    }

    @Test
    void uncertainResultImmediatelyOpensCircuitAndHasNoAutomaticRetryTime() {
        XianyuKamiConfig config = config();

        ExternalSupplyPolicy.Failure failure = ExternalSupplyPolicy.markFailure(config, true, now);

        assertEquals("OPEN", failure.circuitState());
        assertNull(failure.nextRetryTime());
        assertEquals(now, config.getExternalCircuitOpenedAt());
    }

    @Test
    void deterministicFailuresOpenOnlyAtConfiguredThreshold() {
        XianyuKamiConfig config = config();
        config.setExternalFailureThreshold(2);

        ExternalSupplyPolicy.Failure first = ExternalSupplyPolicy.markFailure(config, false, now);
        ExternalSupplyPolicy.Failure second = ExternalSupplyPolicy.markFailure(config, false, now.plusSeconds(20));

        assertEquals("CLOSED", first.circuitState());
        assertNotNull(first.nextRetryTime());
        assertEquals("OPEN", second.circuitState());
    }

    @Test
    void openCircuitCannotBeUsedBeforeCooldown() {
        XianyuKamiConfig config = config();
        config.setExternalCircuitState("OPEN");
        config.setExternalCircuitOpenedAt(now.minusSeconds(60));
        config.setExternalCooldownSeconds(300);

        assertThrows(BusinessException.class, () -> ExternalSupplyPolicy.admit(config, 1, now));
    }

    @Test
    void expiredOpenCircuitAllowsOneHalfOpenProbeAndSuccessClosesIt() {
        XianyuKamiConfig config = config();
        config.setExternalCircuitState("OPEN");
        config.setExternalCircuitOpenedAt(now.minusSeconds(301));
        config.setExternalCooldownSeconds(300);

        ExternalSupplyPolicy.Admission admission = ExternalSupplyPolicy.admit(config, 1, now);
        assertEquals("HALF_OPEN", admission.circuitState());
        assertThrows(BusinessException.class, () -> ExternalSupplyPolicy.admit(config, 1, now));

        ExternalSupplyPolicy.markSuccess(config);
        assertEquals("CLOSED", config.getExternalCircuitState());
        assertEquals(0, config.getExternalConsecutiveFailures());
        assertNull(config.getExternalCircuitOpenedAt());
    }

    private XianyuKamiConfig config() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setExternalCircuitState("CLOSED");
        config.setExternalConsecutiveFailures(0);
        config.setExternalFailureThreshold(3);
        config.setExternalCooldownSeconds(300);
        config.setExternalQuotaUsed(0);
        return config;
    }
}
