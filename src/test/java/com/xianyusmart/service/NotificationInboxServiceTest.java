package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationInboxServiceTest {

    private JdbcTemplate jdbcTemplate;
    private NotificationInboxService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new NotificationInboxService(jdbcTemplate, mock(AccountAccessService.class), new ObjectMapper());
        TenantContext.set(8L);
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    @Test
    void internalRenewalReferencesAreNotPersistedInPublicInboxData() {
        service.record("CREDENTIAL_EXPIRED", 3L, "凭证失效", "请扫码",
                Map.of("_renewalImage", "secret-image-ref", "accountName", "店铺甲"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        String json = String.valueOf(args.getValue()[11]);
        assertFalse(json.contains("secret-image-ref"));
        assertTrue(json.contains("店铺甲"));
    }
}
