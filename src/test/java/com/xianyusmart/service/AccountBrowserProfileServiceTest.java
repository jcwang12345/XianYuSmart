package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuDeviceProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountBrowserProfileServiceTest {

    @Test
    void createsDesktopProfileForTheRequestedAccount() {
        XianyuDeviceProfile result = AccountBrowserProfileService.newProfile(27L);
        assertEquals(27L, result.getXianyuAccountId());
        assertEquals("DESKTOP_WEB", result.getProfileType());
        assertNotNull(result.getProfileKey());
        assertEquals(36, result.getProfileKey().length());
        assertEquals("WINDOWS", result.getPlatform());
        assertEquals("149.0.7827.55", result.getBrowserVersion());
        assertEquals(1536, result.getViewportWidth());
        assertEquals(864, result.getViewportHeight());
        assertEquals("zh-CN", result.getLocale());
        assertEquals("Asia/Shanghai", result.getTimezoneId());
    }

    @Test
    void viewportAndDesktopUaAreStable() {
        assertArrayEquals(AccountBrowserProfileService.viewportFor("stable-key"),
                AccountBrowserProfileService.viewportFor("stable-key"));
        assertArrayEquals(new int[]{1365, 768}, AccountBrowserProfileService.viewportForAccount(1L));
        assertArrayEquals(new int[]{1440, 900}, AccountBrowserProfileService.viewportForAccount(2L));
        String ua = AccountBrowserProfileService.userAgent("WINDOWS", "146.0.12.3");
        assertTrue(ua.contains("Windows NT 10.0"));
        assertTrue(ua.contains("Chrome/146.0.12.3"));
        assertFalse(ua.contains("Mobile"));
        assertFalse(ua.contains("Linux"));
    }

    @Test
    void assignsMatchingDesktopHeadersPerAccount() {
        assertEquals("WINDOWS", AccountBrowserProfileService.platformForAccount(1L));
        assertEquals("MACOS", AccountBrowserProfileService.platformForAccount(2L));
        assertEquals("\"Windows\"", AccountBrowserProfileService.desktopHeaders("WINDOWS", null)
                .get("Sec-Ch-Ua-Platform"));
        assertEquals("\"macOS\"", AccountBrowserProfileService.desktopHeaders("MACOS", null)
                .get("Sec-Ch-Ua-Platform"));
        assertTrue(AccountBrowserProfileService.userAgent("MACOS", null).contains("Macintosh"));
        assertTrue(AccountBrowserProfileService.userAgent("WINDOWS", null)
                .contains("Chrome/149.0.7827.55"));
        assertFalse(AccountBrowserProfileService.userAgentOverride("MACOS", null).toString()
                .contains("Linux"));
    }
}
