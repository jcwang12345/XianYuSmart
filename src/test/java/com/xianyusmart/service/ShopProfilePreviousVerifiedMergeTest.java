package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ShopProfilePreviousVerifiedMergeTest {

    @Test
    void everyTargetFieldCanRetainOnlyPreviousVerifiedValueWithOriginalObservation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ShopProfilePersistenceService persistence = new ShopProfilePersistenceService(mock(JdbcTemplate.class),
                mapper, mock(OperationLogService.class));
        List<String> fields = List.of("shopNickname", "avatarUrl", "region", "shopLevel", "shopScore",
                "superSeller", "sellerCredit", "buyerCredit", "sesameVerified", "realNameVerified",
                "xianyuExpert", "userType", "xianyuUpgraded", "taobaoBound", "pinLimit", "onSaleCount",
                "soldCount", "followerCount", "positiveRate", "totalReviewCount", "totalBoughtCount",
                "totalSoldCount", "lastActiveTime", "platformOnlineStatus", "shopAuthorization");
        Map<String, Object> previous = new LinkedHashMap<>(); previous.put("id", 99L);
        Map<String, ShopProfileParser.FieldEvidence> priorEvidence = new LinkedHashMap<>();
        for (String field : fields) {
            previous.put(field, 7);
            priorEvidence.put(field, new ShopProfileParser.FieldEvidence("VERIFIED", "PLATFORM_API",
                    "$." + field, null, "2026-09-15T01:02:03Z"));
        }
        previous.put("fieldEvidenceJson", mapper.writeValueAsString(priorEvidence));
        Map<String, ShopProfileParser.FieldEvidence> current = new LinkedHashMap<>();

        for (String field : fields) {
            Object value = ReflectionTestUtils.invokeMethod(persistence, "stale", field, null, previous, current);
            assertEquals(7, value, field);
            assertEquals("STALE", current.get(field).status(), field);
            assertEquals("2026-09-15T01:02:03Z", current.get(field).observedAt(), field);
            assertTrue(current.get(field).reason().contains("快照 99"), field);
        }

        previous.put("fieldEvidenceJson", "{}");
        assertNull(ReflectionTestUtils.invokeMethod(persistence, "stale", "shopNickname", null, previous,
                new LinkedHashMap<>()), "unverified legacy value must not be reused");
    }
}
