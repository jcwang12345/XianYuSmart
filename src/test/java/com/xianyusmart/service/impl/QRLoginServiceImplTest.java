package com.xianyusmart.service.impl;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.QRLoginSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QRLoginServiceImplTest {

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
