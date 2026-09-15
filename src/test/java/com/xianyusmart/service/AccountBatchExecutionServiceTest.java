package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountBatchExecutionServiceTest {
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final WebSocketService websocket = mock(WebSocketService.class);
    private final CredentialRenewalService renewal = mock(CredentialRenewalService.class);

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void qaDisableAndEnablePersistLocalStatusWithoutCallingPlatform() {
        TenantContext.set(1L);
        XianyuAccount account = account(101L, 1);
        when(accounts.selectById(101L)).thenReturn(account);
        AccountBatchExecutionService service = new AccountBatchExecutionService(
                accounts, websocket, renewal, true, 1L, "101,102,103");

        Map<String, Object> disabled = service.execute("DISABLE", 101L);
        assertEquals(0, account.getStatus());
        assertEquals("QA_MOCK", disabled.get("executionChannel"));
        verify(websocket, never()).stopWebSocket(anyLong());

        Map<String, Object> enabled = service.execute("ENABLE", 101L);
        assertEquals(1, account.getStatus());
        assertEquals("ENABLED_AND_CONNECTED", enabled.get("outcome"));
        verify(websocket, never()).startWebSocket(anyLong());
        verify(accounts, times(2)).updateById(account);
    }

    @Test
    void realRenewalQueuesPrivatePerAccountFlow() {
        TenantContext.set(9L);
        when(accounts.selectById(202L)).thenReturn(account(202L, 1));
        AccountBatchExecutionService service = new AccountBatchExecutionService(
                accounts, websocket, renewal, true, 1L, "101,102,103");

        Map<String, Object> result = service.execute("RENEW", 202L);
        assertEquals("LOCAL_RUNTIME", result.get("executionChannel"));
        assertEquals("RENEWAL_PREPARATION_ACCEPTED", result.get("outcome"));
        verify(renewal).request(202L);
    }

    @Test
    void disabledAccountCannotSyncOrRenew() {
        TenantContext.set(1L);
        when(accounts.selectById(101L)).thenReturn(account(101L, 0));
        AccountBatchExecutionService service = new AccountBatchExecutionService(
                accounts, websocket, renewal, true, 1L, "101");
        assertThrows(IllegalStateException.class, () -> service.execute("SYNC", 101L));
        assertThrows(IllegalStateException.class, () -> service.execute("RENEW", 101L));
    }

    private static XianyuAccount account(long id, int status) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id); account.setTenantId(1L); account.setStatus(status); account.setAccountNote("QA " + id);
        return account;
    }
}
