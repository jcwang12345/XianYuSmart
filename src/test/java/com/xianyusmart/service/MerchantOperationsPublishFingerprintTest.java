package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MerchantOperationsPublishFingerprintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresTransportFieldsAndMapOrdering() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("requestId", "publish-one");
        first.put("name", "测试商品");
        first.put("amount", new BigDecimal("19.90"));
        first.put("images", List.of("https://example.test/a.jpg"));

        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("images", List.of("https://example.test/a.jpg"));
        replay.put("amount", 19.9);
        replay.put("name", "测试商品");
        replay.put("requestId", "publish-two");
        replay.put("dryRun", false);
        replay.put("payloadFingerprint", "stale");
        replay.put("previewToken", "transport-only-token");

        assertEquals(MerchantOperationsService.publishPayloadFingerprint(mapper, first),
                MerchantOperationsService.publishPayloadFingerprint(mapper, replay));
    }

    @Test
    void changesWhenAnyBusinessFactChanges() {
        Map<String, Object> original = Map.of(
                "name", "测试商品",
                "amount", new BigDecimal("19.90"),
                "stock", 3,
                "publishChannel", "QR_COOKIE",
                "images", List.of("https://example.test/a.jpg"));
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put("stock", 4);

        assertNotEquals(MerchantOperationsService.publishPayloadFingerprint(mapper, original),
                MerchantOperationsService.publishPayloadFingerprint(mapper, changed));
    }

    @Test
    void validatesPublishMoneyPrecisionAndUpperBound() {
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("0.001"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("1.234"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("0"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("100000000.00"), 1));
        assertDoesNotThrow(() -> MerchantOperationsService.validatePublishAmountAndStock(
                new BigDecimal("99999999.99"), 1));
    }

    @Test
    void validatesStockAsPositiveInteger() {
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("1.00"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> MerchantOperationsService.validatePublishAmountAndStock(new BigDecimal("1.00"), "1.5"));
        assertDoesNotThrow(() -> MerchantOperationsService.validatePublishAmountAndStock(
                new BigDecimal("1.00"), "2"));
    }

    @Test
    void qaUnknownEvidenceAlwaysDeclaresNoPlatformWrite() {
        Map<String, Object> evidence = MerchantOperationsService.qaUnknownEvidence();

        assertEquals("QA_MOCK", evidence.get("executionChannel"));
        assertEquals("QA_FIXTURE", evidence.get("dataSource"));
        assertFalse((Boolean) evidence.get("platformNetworkCalls"));
        assertEquals("NOT_PERFORMED", evidence.get("platformWrite"));
        assertEquals("UNKNOWN", evidence.get("outcomeState"));
    }

    @Test
    void merchantOperationsUseTenantContextInsteadOfOperatorId() {
        UserContext.set(91L, "qa-member", 1L);
        try {
            assertEquals(1L, MerchantOperationsService.currentTenantId());
        } finally {
            UserContext.clear();
        }
    }
}
