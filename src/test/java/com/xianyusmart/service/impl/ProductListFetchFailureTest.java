package com.xianyusmart.service.impl;

import com.xianyusmart.controller.dto.ItemListReqDTO;
import com.xianyusmart.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductListFetchFailureTest {
    private static class SyntheticListService extends ItemServiceImpl {
        String response;int calls;
        @Override protected String callOnSaleListApi(Map<String,Object> data,String cookie,Long account) {calls++;return response;}
    }
    private SyntheticListService service;
    private AccountService accounts;
    private CookieRefreshService refresh;
    private ItemListReqDTO request;
    @BeforeEach void setup() {
        service=new SyntheticListService();accounts=mock(AccountService.class);refresh=mock(CookieRefreshService.class);
        when(accounts.getCookieByAccountId(1L)).thenReturn("unb=QA-SYNC-user; _m_h5_tk=synthetic_only");
        ReflectionTestUtils.setField(service,"accountService",accounts);ReflectionTestUtils.setField(service,"cookieRefreshService",refresh);
        var profiles=mock(AccountBrowserProfileService.class);when(profiles.headersForAccount(1L)).thenReturn(Map.of());
        ReflectionTestUtils.setField(service,"accountBrowserProfileService",profiles);
        request=new ItemListReqDTO();request.setCookieId("1");request.setPageNumber(1);request.setPageSize(20);
    }
    @Test void emptyAndMalformedResponsesHaveStableCodesAndDoNotInvalidateCredentials() {
        for(String response:new String[]{null,"","{}"}) {
            service.response=response;
            var result=service.fetchOnSalePage(request);assertEquals(502,result.getCode());
            assertEquals(response==null||response.isBlank()?"EMPTY_RESPONSE":"INVALID_RESPONSE",result.getData().getErrorCode());
            assertFalse(result.getData().getTerminalPage());verifyNoInteractions(refresh);
            verify(accounts,never()).updateCookieStatus(anyLong(),anyInt());
        }
    }
    @Test void confirmedTokenExpiryRefreshesOnceAndRetriesOnlyOnce() {
        when(refresh.refreshCookie(1L)).thenReturn(true);
        {
            service.response="{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED::synthetic\"]}";
            var result=service.fetchOnSalePage(request);assertEquals("CREDENTIAL_EXPIRED",result.getData().getErrorCode());
            verify(refresh,times(1)).refreshCookie(1L);
            assertEquals(2,service.calls);
            verify(accounts,never()).updateCookieStatus(anyLong(),anyInt());
        }
    }
    @Test void verificationAndPermissionFailuresDoNotRefreshOrStartQr() {
        for(var failure:Map.of("FAIL_SYS_USER_VALIDATE","VERIFICATION_REQUIRED","FAIL_SYS_NO_PERMISSION","PLATFORM_PERMISSION_DENIED","FAIL_SYS_TRAFFIC_LIMIT","RATE_LIMITED").entrySet()) {
            service.response="{\"ret\":[\""+failure.getKey()+"::synthetic\"]}";
            assertEquals(failure.getValue(),service.fetchOnSalePage(request).getData().getErrorCode());verifyNoInteractions(refresh);
            verify(accounts,never()).updateCookieStatus(anyLong(),anyInt());
        }
    }
}
