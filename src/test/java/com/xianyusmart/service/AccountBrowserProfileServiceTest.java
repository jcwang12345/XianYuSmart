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
        assertTrue(result.getViewportWidth() >= 1365);
        assertEquals("zh-CN", result.getLocale());
        assertEquals("Asia/Shanghai", result.getTimezoneId());
    }

    @Test
    void viewportAndDesktopUaAreStable() {
        assertArrayEquals(AccountBrowserProfileService.viewportFor("stable-key"),
                AccountBrowserProfileService.viewportFor("stable-key"));
        String ua = AccountBrowserProfileService.userAgent("LINUX", "146.0.12.3");
        assertTrue(ua.contains("X11; Linux x86_64"));
        assertTrue(ua.contains("Chrome/146.0.12.3"));
        assertFalse(ua.contains("Mobile"));
    }
}
