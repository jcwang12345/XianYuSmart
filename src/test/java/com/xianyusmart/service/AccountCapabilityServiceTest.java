package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountCapabilityServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AccountCapabilityService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new AccountCapabilityService(jdbcTemplate, mock(AccountAccessService.class));
        TenantContext.set(7L);
        AccountScopeContext.clear();
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void probePersistsExplicitPlatformPermissionStateWithoutShorteningIt() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 101L, "status", 1, "cookie_status", 1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.probe();

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(11)).update(anyString(), arguments.capture());
        assertTrue(arguments.getAllValues().stream()
                .anyMatch(values -> List.of(values).contains("REQUIRES_PLATFORM_PERMISSION")));
    }
}
