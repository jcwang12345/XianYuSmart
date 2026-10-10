package com.xianyusmart.utils;

import com.xianyusmart.service.AccountBrowserProfileService;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.CookieRefreshService;
import com.xianyusmart.service.PlatformWritePolicy;
import com.xianyusmart.service.RiskControlService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XianyuApiCallUtilsTest {

    @Test
    void transportFailureWithoutResponseHasUnknownOutcome() {
        assertTrue(new XianyuApiCallUtils.ApiCallResult(false, null,
                "调用异常: Read timed out", false).isOutcomeUnknown());
        assertTrue(new XianyuApiCallUtils.ApiCallResult(false, null,
                "响应为空", false).isOutcomeUnknown());
    }

    @Test
    void explicitPlatformRejectionIsNotAnUnknownOutcome() {
        assertFalse(new XianyuApiCallUtils.ApiCallResult(false,
                "{\"ret\":[\"FAIL_BIZ\"]}", "平台拒绝", false).isOutcomeUnknown());
        assertFalse(new XianyuApiCallUtils.ApiCallResult(false,
                null, "账号风控冷却中", false).isOutcomeUnknown());
    }

    @Test
    void expiredTokenIsNotReplayedWithUnchangedCookie() {
        assertFalse(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old", "_m_h5_tk=old"));
        assertFalse(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old", null));
        assertFalse(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old", "  "));
        assertFalse(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old; other=1", "_m_h5_tk=old; other=2"));
        assertTrue(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old", "_m_h5_tk=new"));
        assertTrue(XianyuApiCallUtils.hasFreshCookieForRetry("_m_h5_tk=old; _m_h5_tk_enc=old",
                "_m_h5_tk=old; _m_h5_tk_enc=new"));
    }

    @Test
    void successfulLoginCheckWithoutNewCookieDoesNotReplayExpiredPlatformCall() {
        class ExpiredApi extends XianyuApiCallUtils {
            int calls;
            @Override
            protected XianyuApiUtils.ApiCallResultWithHeaders callPlatformApi(String apiName, String version,
                    Map<String, Object> data, String cookies, String spm, String referer,
                    Map<String, String> headers, Map<String, String> query) {
                calls++;
                return new XianyuApiUtils.ApiCallResultWithHeaders(
                        "{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED::expired\"]}", Map.of());
            }
        }
        ExpiredApi utility = new ExpiredApi();
        CookieRefreshService refresh = mock(CookieRefreshService.class);
        AccountService accounts = mock(AccountService.class);
        RiskControlService risk = mock(RiskControlService.class);
        AccountBrowserProfileService profiles = mock(AccountBrowserProfileService.class);
        PlatformWritePolicy writes = mock(PlatformWritePolicy.class);
        ReflectionTestUtils.setField(utility, "cookieRefreshService", refresh);
        ReflectionTestUtils.setField(utility, "accountService", accounts);
        ReflectionTestUtils.setField(utility, "riskControlService", risk);
        ReflectionTestUtils.setField(utility, "accountBrowserProfileService", profiles);
        ReflectionTestUtils.setField(utility, "platformWritePolicy", writes);
        when(risk.checkApiWrite(eq(9L), anyString())).thenReturn(new RiskControlService.GuardDecision(
                true, RiskControlService.GuardState.NORMAL, 0, 0, "", null));
        when(profiles.headersForAccount(9L)).thenReturn(Map.of());
        when(refresh.refreshCookie(9L)).thenReturn(true);
        when(accounts.getCookieByAccountId(9L)).thenReturn("_m_h5_tk=old");
        var result = utility.callApiWithRetry(9L, "test.read", Map.of(), "_m_h5_tk=old");
        assertFalse(result.isSuccess());
        assertTrue(result.isTokenExpired());
        assertTrue(result.getErrorMessage().contains("扫码更新登录"));
        assertTrue(utility.calls == 1);
        verify(refresh, times(1)).refreshCookie(9L);
    }
}
