package com.xianyusmart.controller;

import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.ListingDraftService;
import com.xianyusmart.service.MerchantOperationsService;
import com.xianyusmart.service.PublishCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishingControllerTest {
    private final ListingDraftService drafts = mock(ListingDraftService.class);
    private final MerchantOperationsService operations = mock(MerchantOperationsService.class);
    private final PublishingController controller = new PublishingController(mock(PublishCapabilityService.class), operations, drafts);

    @Test
    void unsupportedFieldsBlockBeforePlatformPreflightOrSnapshot() {
        Map<String, Object> request = Map.of("leafCategoryCode", "LOCAL_ONLY");
        when(drafts.requirePreflightEligible(request)).thenThrow(new BusinessException(409, "不支持指定类目"));
        assertEquals(409, assertThrows(BusinessException.class, () -> controller.preflight(request)).getCode());
        verifyNoInteractions(operations);
        verify(drafts, never()).recordPreflight(anyMap(), anyMap(), anyMap());
    }

    @Test
    void unconfirmedRecommendationDoesNotPassOrIssueExecutableToken() {
        Map<String, Object> request = Map.of("categoryMode", "BASIC_PLATFORM_RECOMMENDED");
        when(drafts.requirePreflightEligible(request)).thenReturn(Map.of("valid", true));
        when(operations.createPublishPlan(anyMap())).thenReturn(Map.of("valid", true, "outcomeState", "PREFLIGHT_PASSED",
                "platform", Map.of("category", Map.of("catId", "50023914"))));
        var result = controller.preflight(request).getData();
        assertEquals(false, result.get("valid"));
        assertEquals(true, result.get("requiresCategoryConfirmation"));
        assertEquals("CATEGORY_CONFIRMATION_REQUIRED", result.get("outcomeState"));
        assertFalse(result.containsKey("previewToken"));
        verify(drafts, never()).recordPreflight(anyMap(), anyMap(), anyMap());
    }

    @Test
    void confirmedRecommendationCanIssueSnapshot() {
        Map<String, Object> request = Map.of("categoryMode", "BASIC_PLATFORM_RECOMMENDED", "confirmedPlatformCategoryId", "50023914");
        when(drafts.requirePreflightEligible(request)).thenReturn(Map.of("valid", true));
        when(operations.createPublishPlan(anyMap())).thenReturn(Map.of("valid", true, "outcomeState", "PREFLIGHT_PASSED"));
        when(drafts.preflightCategoryConfirmed(eq(request), anyMap())).thenReturn(true);
        when(drafts.recordPreflight(eq(request), anyMap(), anyMap())).thenReturn(Map.of("previewToken", "synthetic-token"));
        assertEquals("synthetic-token", controller.preflight(request).getData().get("previewToken"));
    }

    @Test
    void executeRejectsMissingConfirmationBeforeSnapshotConsumptionOrPlatformWrite() {
        Map<String, Object> request = Map.of("categoryMode", "BASIC_PLATFORM_RECOMMENDED");
        when(drafts.requireExecutable(request)).thenThrow(new BusinessException(409, "请确认类目"));
        assertEquals(409, assertThrows(BusinessException.class, () -> controller.execute(request)).getCode());
        verify(drafts, never()).consumePreflight(anyMap());
        verifyNoInteractions(operations);
    }
}
