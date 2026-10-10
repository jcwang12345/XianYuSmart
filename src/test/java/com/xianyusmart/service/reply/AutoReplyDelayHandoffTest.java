package com.xianyusmart.service.reply;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyusmart.service.AiHandoffService;
import com.xianyusmart.service.AutoReplyService;
import com.xianyusmart.service.BuyerProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

class AutoReplyDelayHandoffTest {
    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void pendingHandoffPausesOnlyTheClaimedReplyWithAnExplicitReason() {
        Fixture fixture = new Fixture(true);

        fixture.execute();

        verify(fixture.records).pauseForActiveHandoff(42L);
        verify(fixture.records, never()).cancelById(42L);
        verify(fixture.autoReply, never()).executeAutoReply(anyList(), anyLong());
    }

    @Test
    void resolvedHandoffAllowsTheNextMessageToUseAutoReply() {
        Fixture fixture = new Fixture(false);

        fixture.execute();

        verify(fixture.autoReply).executeAutoReply(anyList(), eq(42L));
        verify(fixture.records, never()).pauseForActiveHandoff(anyLong());
    }

    private static class Fixture {
        final AutoReplyDelayServiceImpl service = new AutoReplyDelayServiceImpl();
        final XianyuGoodsAutoReplyRecordMapper records = mock(XianyuGoodsAutoReplyRecordMapper.class);
        final AutoReplyService autoReply = mock(AutoReplyService.class);
        final AiHandoffService handoffs = mock(AiHandoffService.class);

        Fixture(boolean hasPending) {
            com.xianyusmart.service.AccountPolicyFixture.active(service);
            XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
            XianyuAccount account = new XianyuAccount();
            account.setId(1L);
            account.setTenantId(1L);
            account.setStatus(1);
            when(accounts.selectById(1L)).thenReturn(account);
            when(handoffs.hasPending(1L, "session-1")).thenReturn(hasPending);
            ReflectionTestUtils.setField(service, "accountMapper", accounts);
            ReflectionTestUtils.setField(service, "autoReplyRecordMapper", records);
            ReflectionTestUtils.setField(service, "autoReplyService", autoReply);
            ReflectionTestUtils.setField(service, "aiHandoffService", handoffs);
            ReflectionTestUtils.setField(service, "takeoverManager", mock(HumanTakeoverManager.class));
            ReflectionTestUtils.setField(service, "buyerProfileService", mock(BuyerProfileService.class));
        }

        void execute() {
            ChatMessageData message = new ChatMessageData();
            message.setXianyuAccountId(1L);
            message.setSId("session-1");
            message.setSenderUserId("buyer-1");
            ReflectionTestUtils.invokeMethod(service, "executeClaimedTask", 42L, List.of(message), "lease-1");
        }
    }
}
