package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuBuyerProfile;
import com.xianyusmart.entity.XianyuBuyerProfileRequest;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileRequestMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyerProfileServiceTest {

    private XianyuBuyerProfileMapper profileMapper;
    private XianyuAccountMapper accountMapper;
    private XianyuBuyerProfileRequestMapper requestMapper;
    private OperationLogService operationLogService;
    private BuyerProfileService service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(XianyuBuyerProfileMapper.class);
        accountMapper = mock(XianyuAccountMapper.class);
        requestMapper = mock(XianyuBuyerProfileRequestMapper.class);
        operationLogService = mock(OperationLogService.class);
        service = new BuyerProfileService(profileMapper, accountMapper, new ObjectMapper().findAndRegisterModules(),
                mock(XianyuGoodsOrderMapper.class), mock(XianyuChatMessageMapper.class),
                requestMapper, operationLogService);
        TenantContext.set(7L);
        when(accountMapper.selectById(anyLong())).thenReturn(new XianyuAccount());
        when(profileMapper.selectPage(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());
        when(requestMapper.reserve(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(requestMapper.complete(anyLong(), anyString(), anyString())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void blacklistFromBuyer360ForcesAutomationBlockAndProjectsToAllConversations() {
        XianyuBuyerProfile profile = profile(false, null);
        when(profileMapper.findByBuyer(101L, "buyer-1")).thenReturn(profile);

        BuyerProfileSaveReqDTO request = request(true, false, null);
        service.save(request);

        assertEquals(1, profile.getBlacklisted());
        assertEquals(1, profile.getAutomationBlocked());
        assertEquals("BUYER_360", profile.getBlacklistSource());
        assertNotNull(profile.getBlacklistUpdatedTime());
        assertEquals("[买家] 已加入客户黑名单", profile.getBlockedReason());
        verify(profileMapper).updateAutomationAndBlacklist(7L, 101L, "buyer-1", 1,
                "[买家] 已加入客户黑名单", 1, "BUYER_360", profile.getBlacklistUpdatedTime());
        verify(profileMapper).updateConversationBlacklist(7L, 101L, "buyer-1", 1);
        ArgumentCaptor<XianyuOperationLog> audit = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(operationLogService).logRequired(audit.capture());
        assertEquals("BUYER_PROFILE_UPDATE", audit.getValue().getOperationType());
        assertEquals("buyer-1", audit.getValue().getTargetId());
        assertEquals("buyer-profile-test", audit.getValue().getRequestId());
        org.junit.jupiter.api.Assertions.assertTrue(audit.getValue().getFieldDiffJson().contains("blacklisted"));
        org.junit.jupiter.api.Assertions.assertTrue(audit.getValue().getRequestParams().contains("before"));
    }

    @Test
    void removingBlacklistClearsOnlyBlacklistOwnedBlockReason() {
        XianyuBuyerProfile profile = profile(true, "[买家] 已加入客户黑名单");
        when(profileMapper.findByBuyer(101L, "buyer-1")).thenReturn(profile);

        service.save(request(false, false, "[买家] 已加入客户黑名单"));

        assertEquals(0, profile.getBlacklisted());
        assertEquals(0, profile.getAutomationBlocked());
        assertNull(profile.getBlockedReason());
        verify(profileMapper).updateAutomationAndBlacklist(7L, 101L, "buyer-1", 0,
                null, 0, "BUYER_360", profile.getBlacklistUpdatedTime());
        verify(profileMapper).updateConversationBlacklist(7L, 101L, "buyer-1", 0);
    }

    @Test
    void exactRequestReplayReturnsStoredResponseWithoutDuplicateMutationOrAudit() throws Exception {
        XianyuBuyerProfile profile = profile(false, null);
        when(profileMapper.findByBuyer(101L, "buyer-1")).thenReturn(profile);
        com.xianyusmart.controller.dto.BuyerProfileRespDTO response =
                new com.xianyusmart.controller.dto.BuyerProfileRespDTO();
        response.setXianyuAccountId(101L);
        response.setBuyerUserId("buyer-1");
        response.setBlacklisted(true);
        response.setTags(List.of());
        when(profileMapper.selectDetail(101L, "buyer-1")).thenReturn(response);

        BuyerProfileSaveReqDTO request = request(true, false, null);
        service.save(request);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(requestMapper).reserve(eq(7L), eq(101L), eq("buyer-1"), eq("buyer-profile-test"),
                eq("buyer-profile-test"), fingerprint.capture());
        XianyuBuyerProfileRequest stored = new XianyuBuyerProfileRequest();
        stored.setRequestFingerprint(fingerprint.getValue());
        stored.setResponseJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(response));
        when(requestMapper.reserve(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        when(requestMapper.findByRequestId(7L, "buyer-profile-test")).thenReturn(stored);

        assertEquals("buyer-1", service.save(request).getBuyerUserId());
        verify(profileMapper, times(1)).updateConversationBlacklist(7L, 101L, "buyer-1", 1);
        verify(operationLogService, times(1)).logRequired(any(XianyuOperationLog.class));
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsRejectedBeforeMutation() {
        XianyuBuyerProfileRequest stored = new XianyuBuyerProfileRequest();
        stored.setRequestFingerprint("different-fingerprint");
        stored.setResponseJson("null");
        when(requestMapper.reserve(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        when(requestMapper.findByRequestId(7L, "buyer-profile-test")).thenReturn(stored);

        com.xianyusmart.exception.BusinessException error = assertThrows(
                com.xianyusmart.exception.BusinessException.class,
                () -> service.save(request(true, false, null)));

        assertEquals(409, error.getCode());
        verify(profileMapper, never()).updateConversationBlacklist(anyLong(), anyLong(), anyString(), anyInt());
        verify(operationLogService, never()).logRequired(any());
    }

    @Test
    void requiredAuditFailureStopsRequestCompletionSoTransactionCanRollBack() {
        XianyuBuyerProfile profile = profile(false, null);
        when(profileMapper.findByBuyer(101L, "buyer-1")).thenReturn(profile);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(operationLogService).logRequired(any(XianyuOperationLog.class));

        assertThrows(IllegalStateException.class, () -> service.save(request(true, false, null)));

        verify(requestMapper, never()).complete(anyLong(), anyString(), anyString());
    }

    private XianyuBuyerProfile profile(boolean blacklisted, String reason) {
        XianyuBuyerProfile profile = new XianyuBuyerProfile();
        profile.setId(12L);
        profile.setTenantId(7L);
        profile.setXianyuAccountId(101L);
        profile.setBuyerUserId("buyer-1");
        profile.setBlacklisted(blacklisted ? 1 : 0);
        profile.setAutomationBlocked(blacklisted ? 1 : 0);
        profile.setBlockedReason(reason);
        return profile;
    }

    private BuyerProfileSaveReqDTO request(boolean blacklisted, boolean blocked, String reason) {
        BuyerProfileSaveReqDTO request = new BuyerProfileSaveReqDTO();
        request.setRequestId("buyer-profile-test");
        request.setIdempotencyKey("buyer-profile-test");
        request.setXianyuAccountId(101L);
        request.setBuyerUserId("buyer-1");
        request.setBuyerUserName("测试买家");
        request.setTags(List.of());
        request.setBlacklisted(blacklisted);
        request.setAutomationBlocked(blocked);
        request.setBlockedReason(reason);
        return request;
    }
}
