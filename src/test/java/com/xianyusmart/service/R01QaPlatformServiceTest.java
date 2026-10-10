package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class R01QaPlatformServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void refusesProductionActivation() {
        var s=new R01QaPlatformService(new MockEnvironment(),mock(JdbcTemplate.class),true,1,"1,2");
        assertThrows(IllegalStateException.class,s::validate);
    }
    @Test void disabledNeverReadsCredentialsOrCreatesFacts() {
        var jdbc=mock(JdbcTemplate.class);
        var s=new R01QaPlatformService(new MockEnvironment(),jdbc,false,1,"1");
        TenantContext.set(1L);
        assertThrows(RuntimeException.class,()->s.require(1L,"QA-R01-x"));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsWrongTenantAccountPrefixAndCredentialRowsBeforeMockCalls() {
        var env=new MockEnvironment(); env.setActiveProfiles("qa");
        var jdbc=mock(JdbcTemplate.class);
        var s=new R01QaPlatformService(env,jdbc,true,1,"1"); s.validate();
        TenantContext.set(2L);
        assertThrows(RuntimeException.class,()->s.require(1L,"QA-R01-x"));
        TenantContext.set(1L);
        assertThrows(RuntimeException.class,()->s.require(2L,"QA-R01-x"));
        assertThrows(RuntimeException.class,()->s.require(1L,"real-object"));
        verifyNoInteractions(jdbc);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any())).thenReturn(0L);
        assertThrows(RuntimeException.class,()->s.require(1L,"QA-R01-x"));
        assertTrue(s.counts("QA-R01-x").isEmpty());
    }
    @Test void factsAreImmutableAndRiskBlockDoesNotCountAnExternalAttempt() {
        var env=new MockEnvironment(); env.setActiveProfiles("qa");
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any())).thenReturn(1L);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(),any())).thenReturn(1);
        var s=new R01QaPlatformService(env,jdbc,true,1,"1"); TenantContext.set(1L);
        var first=s.register(1L,"QA-R01-order","PAID","RISK_BLOCK","QA-R01-goods","QA-R01-buyer");
        assertEquals(first,s.register(1L,"QA-R01-order","PAID","RISK_BLOCK","QA-R01-goods","QA-R01-buyer"));
        assertThrows(RuntimeException.class,()->s.register(1L,"QA-R01-order","PAID","SUCCESS","QA-R01-goods","QA-R01-buyer"));
        assertEquals(OrderService.CONSIGN_DEFERRED,s.consign(1L,"QA-R01-order"));
        assertEquals(1,s.counts("QA-R01-order").get("riskBlocked"));
        assertFalse(s.counts("QA-R01-order").containsKey("consign"));
    }
}
