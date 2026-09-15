package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuBuyerProfile;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyerProfileServiceTest {

    private XianyuBuyerProfileMapper profileMapper;
    private XianyuAccountMapper accountMapper;
    private BuyerProfileService service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(XianyuBuyerProfileMapper.class);
        accountMapper = mock(XianyuAccountMapper.class);
        service = new BuyerProfileService(profileMapper, accountMapper, new ObjectMapper(),
                mock(XianyuGoodsOrderMapper.class), mock(XianyuChatMessageMapper.class));
        TenantContext.set(7L);
        when(accountMapper.selectById(anyLong())).thenReturn(new XianyuAccount());
        when(profileMapper.selectPage(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());
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
