package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductSyncQaBoundaryTest {
    @AfterEach void clear(){TenantContext.clear();}
    @Test void explicitQaProfileAndFlagAreRequired(){
        var env=new MockEnvironment();assertThrows(IllegalStateException.class,()->new ProductSyncQaFacts(env,mock(JdbcTemplate.class),true,7));
        env.setActiveProfiles("qa");var disabled=new ProductSyncQaFacts(env,mock(JdbcTemplate.class),false,7);TenantContext.set(7L);
        assertEquals(404,assertThrows(BusinessException.class,()->disabled.configure(1L,"QA-SYNC-boundary",ProductSyncQaFacts.Scenario.COMPLETE)).getCode());
    }
    @Test void configuredFixtureProtectionFailureNeverFallsBackToLivePlatform(){
        var env=new MockEnvironment();env.setActiveProfiles("qa");var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L),eq(7L))).thenReturn(1);
        var facts=new ProductSyncQaFacts(env,jdbc,true,7);TenantContext.set(7L);facts.configure(1L,"QA-SYNC-boundary",ProductSyncQaFacts.Scenario.COMPLETE);assertTrue(facts.handles(1L));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L),eq(7L))).thenReturn(0);
        assertEquals(403,assertThrows(BusinessException.class,()->facts.handles(1L)).getCode());
        assertEquals(403,assertThrows(BusinessException.class,()->facts.page(1L,1,20)).getCode());
        TenantContext.set(8L);assertEquals(404,assertThrows(BusinessException.class,()->facts.handles(1L)).getCode());
    }
}
