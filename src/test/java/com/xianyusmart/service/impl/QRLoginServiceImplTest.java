package com.xianyusmart.service.impl;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.QRLoginSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QRLoginServiceImplTest {
    @Test void scanWithDifferentAccountCannotOverwriteCredentials() {
        QRLoginServiceImpl service=new QRLoginServiceImpl();
        var accounts=org.mockito.Mockito.mock(com.xianyusmart.service.AccountService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"accountService",accounts);
        org.mockito.Mockito.when(accounts.getXianyuUserId(1L)).thenReturn("expected");
        QRLoginSession session=new QRLoginSession("mismatch");session.setTenantId(7L);session.setTargetAccountId(1L);session.setUnb("different");
        session.getCookies().put("unb","different");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",session);
        assertEquals("error",session.getStatus());
        org.mockito.Mockito.verify(accounts,org.mockito.Mockito.never()).updateAccountCookie(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionIsVisibleOnlyToItsOwningTenant() throws Exception {
        QRLoginServiceImpl service = new QRLoginServiceImpl();
        QRLoginSession session = new QRLoginSession("session-a");
        session.setTenantId(10L);
        Field field = QRLoginServiceImpl.class.getDeclaredField("sessions");
        field.setAccessible(true);
        ((Map<String, QRLoginSession>) field.get(service)).put(session.getSessionId(), session);

        TenantContext.set(10L);
        assertSame(session, service.ownedSession("session-a"));

        TenantContext.set(11L);
        assertNull(service.ownedSession("session-a"));
        assertEquals("not_found", service.getSessionStatus("session-a").getStatus());
    }
}
