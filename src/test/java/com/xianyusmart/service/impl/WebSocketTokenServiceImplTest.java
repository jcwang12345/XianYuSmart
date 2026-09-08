package com.xianyusmart.service.impl;

import com.xianyusmart.service.CookieRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketTokenServiceImplTest {

    @Mock
    private CookieRefreshService cookieRefreshService;

    private WebSocketTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WebSocketTokenServiceImpl();
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookieRefreshService);
    }

    @Test
    void firstRecoveryAttemptUsesLightweightHasLoginRefresh() {
        when(cookieRefreshService.refreshCookie(4L)).thenReturn(true);

        assertTrue(service.refreshCookieForTokenFailure(4L, 0));

        verify(cookieRefreshService).refreshCookie(4L);
        verify(cookieRefreshService, never()).forceBrowserRefresh(4L);
    }

    @Test
    void secondRecoveryAttemptForcesPersistentBrowserRefresh() {
        when(cookieRefreshService.forceBrowserRefresh(4L)).thenReturn(true);

        assertTrue(service.refreshCookieForTokenFailure(4L, 1));

        verify(cookieRefreshService).forceBrowserRefresh(4L);
        verify(cookieRefreshService, never()).refreshCookie(4L);
    }
}
